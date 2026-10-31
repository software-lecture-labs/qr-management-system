package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.api.dto.response.PendingCommandResponse;
import com.restofy.waiter.api.dto.response.TableDetailResponse;
import com.restofy.waiter.api.dto.response.TableSummaryResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.order.OrderPageDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Table queries. Each call authorizes the caller as a WAITER of one restaurant, then composes a snapshot from
 * a constant number of downstream requests (QR tables, one Order batch, one local query): never one request
 * per table. Filters, ordering and totals are applied to the whole table set before paging.
 */
@Service
public class TableQueryService {

    private final QrClient qr;
    private final OrderClient orders;
    private final ServiceRequestRepository serviceRequests;
    private final ServeCommandHints serveCommands;
    private final TableCommandRepository tableCommands;

    public TableQueryService(QrClient qr, OrderClient orders, ServiceRequestRepository serviceRequests,
                             ServeCommandHints serveCommands, TableCommandRepository tableCommands) {
        this.qr = qr;
        this.orders = orders;
        this.serviceRequests = serviceRequests;
        this.serveCommands = serveCommands;
        this.tableCommands = tableCommands;
    }

    public PageResponse<TableSummaryResponse> listTables(AuthenticatedStaff staff, TableFilter filter, PageParams page) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        List<QrTableDto> tables = qr.listAllTables(restaurantId);
        Facts facts = factsFor(restaurantId, tables);
        List<TableSummaryResponse> matching = tables.stream()
                .sorted(NaturalTableOrder.COMPARATOR)
                .map(table -> facts.summary(table))
                .filter(summary -> matches(filter, summary))
                .toList();
        int from = (int) Math.min(page.offset(), matching.size());
        int to = Math.min(from + page.size(), matching.size());
        return PageResponse.of(matching.subList(from, to), page.page(), page.size(), matching.size());
    }

    public TableDetailResponse getTable(AuthenticatedStaff staff, UUID tableId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        QrTableDto table = requireTable(restaurantId, tableId);
        Facts facts = factsFor(restaurantId, List.of(table));
        return new TableDetailResponse(facts.summary(table),
                facts.unresolved(table).stream().map(WaiterMapper::toServiceRequest).toList());
    }

    /** Orders of the table's live session only; orders of earlier sessions are history and never listed here. */
    public PageResponse<OrderResponse> listTableOrders(AuthenticatedStaff staff, UUID tableId, OrderView view,
                                                       PageParams page) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        QrTableDto table = requireTable(restaurantId, tableId);
        QrSessionDto session = table.currentSession();
        if (session == null) return PageResponse.of(List.of(), page.page(), page.size(), 0);
        OrderPageDto result = orders.search(restaurantId, tableId, List.of(session.id()), view, page.page(), page.size());
        var hints = serveCommands.forOrders(restaurantId, result.items().stream().map(order -> order.id()).toList());
        return PageResponse.of(result.items().stream()
                .map(order -> WaiterMapper.toOrder(order, table, hints.get(order.id()))).toList(),
                page.page(), page.size(), result.totalElements());
    }

    private QrTableDto requireTable(UUID restaurantId, UUID tableId) {
        return qr.findTable(restaurantId, tableId)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_NOT_FOUND", "Table not found"));
    }

    private static boolean matches(TableFilter filter, TableSummaryResponse table) {
        return (filter.status() == null || filter.status() == table.operationalStatus())
                && (filter.hasActiveOrder() == null || filter.hasActiveOrder() == table.activeOrderCount() > 0)
                && (filter.hasOpenCall() == null || filter.hasOpenCall() == table.openCallCount() > 0)
                && (filter.billRequested() == null || filter.billRequested() == table.billRequested());
    }

    private Facts factsFor(UUID restaurantId, List<QrTableDto> tables) {
        List<UUID> sessionIds = tables.stream().map(QrTableDto::currentSession).filter(Objects::nonNull)
                .map(QrSessionDto::id).toList();
        Map<UUID, Integer> activeOrders = orders.activeOrderCounts(restaurantId, sessionIds);
        Map<UUID, List<ServiceRequest>> calls = sessionIds.isEmpty() ? Map.of()
                : serviceRequests.findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
                        restaurantId, sessionIds, ServiceRequestStatus.UNRESOLVED).stream()
                .collect(Collectors.groupingBy(ServiceRequest::getTableSessionId));
        // The newest command without a final answer of QR per table: one small query for the whole restaurant.
        Map<UUID, PendingCommandResponse> pending = new HashMap<>();
        for (TableCommand command : tableCommands.findByRestaurantIdAndStatusInOrderByCreatedAtDesc(restaurantId,
                TableCommandStatus.OPEN)) {
            pending.putIfAbsent(command.getTableId(), WaiterMapper.pendingCommand(command));
        }
        return new Facts(activeOrders, calls, pending);
    }

    /** Per-session facts, looked up by the table's live session so earlier sessions never contribute. */
    private record Facts(Map<UUID, Integer> activeOrders, Map<UUID, List<ServiceRequest>> calls,
                         Map<UUID, PendingCommandResponse> pendingCommands) {

        List<ServiceRequest> unresolved(QrTableDto table) {
            QrSessionDto session = table.currentSession();
            return session == null ? List.of() : calls.getOrDefault(session.id(), List.of());
        }

        TableSummaryResponse summary(QrTableDto table) {
            QrSessionDto session = table.currentSession();
            int active = session == null ? 0 : activeOrders.getOrDefault(session.id(), 0);
            return WaiterMapper.toSummary(table, active, unresolved(table), pendingCommands.get(table.id()));
        }
    }
}
