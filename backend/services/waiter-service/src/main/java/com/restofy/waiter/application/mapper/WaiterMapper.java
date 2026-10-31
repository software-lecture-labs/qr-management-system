package com.restofy.waiter.application.mapper;

import com.restofy.waiter.api.dto.response.GuestCallResponse;
import com.restofy.waiter.api.dto.response.NotificationSyncResponse;
import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PendingCommandResponse;
import com.restofy.waiter.api.dto.response.ServeCommandResponse;
import com.restofy.waiter.api.dto.response.ServiceCallResponse;
import com.restofy.waiter.api.dto.response.ServiceRequestResponse;
import com.restofy.waiter.api.dto.response.TableCommandResponse;
import com.restofy.waiter.api.dto.response.TableSessionResponse;
import com.restofy.waiter.api.dto.response.TableSummaryResponse;
import com.restofy.waiter.application.service.OrderServeService;
import com.restofy.waiter.application.service.TableCommandService;
import com.restofy.waiter.domain.entity.OrderServeCommand;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.infrastructure.client.order.CancellationDto;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderItemDto;
import com.restofy.waiter.infrastructure.client.order.OrderItemOptionDto;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries;

import java.util.List;

/**
 * Maps downstream DTOs and local rows to the public API. Guest identifiers and staff ids of downstream
 * records are dropped on purpose: the waiter view does not need them.
 */
public final class WaiterMapper {

    private WaiterMapper() {
    }

    public static int openCallCount(List<ServiceRequest> unresolved) {
        return (int) unresolved.stream().filter(request -> request.getRequestType() != ServiceRequestType.BILL).count();
    }

    public static boolean billRequested(List<ServiceRequest> unresolved) {
        return unresolved.stream().anyMatch(request -> request.getRequestType() == ServiceRequestType.BILL);
    }

    public static TableSummaryResponse toSummary(QrTableDto table, int activeOrderCount, List<ServiceRequest> unresolved,
                                                 PendingCommandResponse pendingCommand) {
        boolean ready = table.active() && table.operationalStatus() == TableOperationalStatus.AVAILABLE
                && table.currentSession() == null;
        return new TableSummaryResponse(table.id(), table.tableNumber(), table.displayName(), table.capacity(),
                table.active(), table.operationalStatus(), toSession(table.currentSession()), activeOrderCount,
                openCallCount(unresolved), billRequested(unresolved), table.version(), ready, pendingCommand);
    }

    /** The newest command of a table that has no final answer of QR yet, as the panel shows it. */
    public static PendingCommandResponse pendingCommand(TableCommand command) {
        return new PendingCommandResponse(command.getId(), command.getType(), command.getStatus(), command.getCreatedAt());
    }

    public static TableCommandResponse toTableCommand(TableCommandService.Result result) {
        TableCommand command = result.command();
        boolean completed = command.getStatus() == TableCommandStatus.COMPLETED;
        TableCommandResponse.Closure closure = completed && command.getType() == TableCommandType.CLOSE
                ? new TableCommandResponse.Closure(command.getQrClosedAt(), command.getEndedGuestSessions(),
                command.getCancelledCallCount(), command.isReconciliationRequired()) : null;
        TableCommandResponse.Cleaning cleaning = completed && command.getType() == TableCommandType.CLEAN
                ? new TableCommandResponse.Cleaning(command.getTableVersionAfter(), Boolean.TRUE.equals(command.getReadyForNewCustomer()))
                : null;
        List<String> blockers = command.getRejectionDetail() == null ? List.of()
                : List.of(command.getRejectionDetail().split(","));
        return new TableCommandResponse(command.getId(), command.getType(), command.getTableId(),
                command.getTableSessionId(), command.getExpectedVersion(), command.getStatus(),
                command.getRejectionCode(), blockers, command.getCreatedAt(), command.getCompletedAt(),
                result.reconciliation(), closure, cleaning);
    }

    public static ServiceRequestResponse toServiceRequest(ServiceRequest request) {
        return new ServiceRequestResponse(request.getId(), request.getRequestType(), request.getStatus(),
                request.getMessage(), request.getCreatedAt(), request.getClaimedAt(), request.getVersion());
    }

    public static ServeCommandResponse toServeCommand(OrderServeService.Result result) {
        OrderServeCommand command = result.command();
        OrderDto order = result.order();
        return new ServeCommandResponse(command.getId(), command.getOrderId(), command.getTableSessionId(),
                command.getExpectedVersion(), command.getStatus(), command.getRejectionCode(), command.getCreatedAt(),
                command.getCompletedAt(), result.reconciliation(),
                order == null ? null : new ServeCommandResponse.ServedOrder(order.id(), order.orderNumber(),
                        order.tableSessionId(), order.businessStatus(), order.deliveryStatus(), order.servedAt(),
                        order.preparation() == null ? null : order.preparation().status(), order.version()));
    }

    public static ServiceCallResponse toCall(ServiceRequest call, NotificationDeliveries.Sync sync) {
        return new ServiceCallResponse(call.getId(), call.getTableId(), call.getTableSessionId(), call.getRequestType(),
                call.getStatus(), call.getMessage(), call.getCreatedAt(), call.getClaimedByUserId(), call.getClaimedAt(),
                call.getResolvedByUserId(), call.getResolvedAt(), call.getVersion(),
                call.getClosureReason() == null ? null : call.getClosureReason().name(), call.getCancelledAt(),
                NotificationSyncResponse.from(sync));
    }

    public static GuestCallResponse toGuestCall(ServiceRequest call) {
        return new GuestCallResponse(call.getId(), call.getRequestType(), call.getStatus(), call.getMessage(),
                call.getCreatedAt());
    }

    public static OrderResponse toOrder(OrderDto order, QrTableDto table) {
        return toOrder(order, table, null);
    }

    public static OrderResponse toOrder(OrderDto order, QrTableDto table, OrderResponse.ServeCommandHint serveCommand) {
        QrSessionDto live = table.currentSession();
        boolean current = live != null && live.id().equals(order.tableSessionId());
        return new OrderResponse(order.id(), order.orderNumber(),
                new OrderResponse.TableRef(table.id(), table.tableNumber(), table.displayName(), table.operationalStatus()),
                new OrderResponse.SessionRef(order.tableSessionId(), current),
                order.businessStatus(), order.deliveryStatus(),
                order.preparation() == null ? null : new OrderResponse.Preparation(order.preparation().status(),
                        order.preparation().startedAt(), order.preparation().readyAt(), order.preparation().asOf()),
                order.payment() == null ? null : new OrderResponse.Payment(order.payment().status(), order.payment().asOf()),
                order.acceptedAt(), order.servedAt(), order.currency(), order.originalGrossTotal(),
                order.cancelledGrossTotal(), order.payableTotal(),
                order.items().stream().map(WaiterMapper::toItem).toList(),
                order.cancellations().stream().map(WaiterMapper::toCancellation).toList(),
                serveCommand, order.version());
    }

    private static TableSessionResponse toSession(QrSessionDto session) {
        return session == null ? null : new TableSessionResponse(session.id(), session.status(), session.openedAt(),
                session.lastActivityAt(), session.scanCount(), session.version());
    }

    private static OrderResponse.Item toItem(OrderItemDto item) {
        return new OrderResponse.Item(item.id(), item.productId(), item.productName(), item.quantity(),
                item.cancelledQuantity(), item.quantity() - item.cancelledQuantity(), item.unitGrossPrice(),
                item.lineGrossTotal(), item.note(),
                item.options().stream().map(WaiterMapper::toOption).toList());
    }

    private static OrderResponse.Option toOption(OrderItemOptionDto option) {
        return new OrderResponse.Option(option.name(), option.action(), option.quantity(), option.unitPriceDelta());
    }

    private static OrderResponse.Cancellation toCancellation(CancellationDto cancellation) {
        return new OrderResponse.Cancellation(cancellation.id(), cancellation.source(), cancellation.status(),
                cancellation.reasonCode(), cancellation.reasonText(), cancellation.requestedAt(),
                cancellation.decidedAt(), cancellation.items().stream()
                .map(item -> new OrderResponse.CancelledItem(item.orderItemId(), item.quantity())).toList());
    }
}
