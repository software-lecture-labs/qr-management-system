package com.restofy.order.application.service;

import com.restofy.order.api.dto.response.OrderDetailResponse;
import com.restofy.order.api.dto.response.OrderHistoryResponse;
import com.restofy.order.api.dto.response.OrderListResponse;
import com.restofy.order.application.mapper.OrderMapper;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads one order and its history for the caller that is entitled to see it. The decision is made here (not only in
 * the HTTP layer) and always inside the caller's own restaurant: knowing an order id grants nothing.
 *
 * <p>Who may read what:
 * <ul>
 *   <li>GUEST: only their OWN orders (same guest session), and only while their table session is ACTIVE.</li>
 *   <li>STAFF ADMIN and WAITER: every order of their own restaurant. ADMIN also sees staff identifiers in the history.</li>
 *   <li>STAFF KITCHEN: the orders and their details of its own restaurant, in a reduced view: what to prepare (lines,
 *       quantities, extras, removed ingredients, notes) and the fulfillment status, but no payment status, amounts or
 *       prices. It cannot filter by payment status and cannot read the history (which has the payment axis and staff
 *       identifiers). The Kitchen service may later build its own read model from the OrderPlaced event.</li>
 * </ul>
 * An order that does not exist, belongs to another restaurant, or to another guest is reported identically as not
 * found, so its existence is not revealed.
 */
@Service
public class OrderQueryService {

    static final Set<StaffRole> STAFF_READERS = Set.of(StaffRole.ADMIN, StaffRole.WAITER, StaffRole.KITCHEN);
    static final Set<StaffRole> HISTORY_READERS = Set.of(StaffRole.ADMIN, StaffRole.WAITER);

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final OrderMapper mapper;

    public OrderQueryService(OrderRepository orders, OrderStatusHistoryStore history, OrderMapper mapper) {
        this.orders = orders;
        this.history = history;
        this.mapper = mapper;
    }

    public OrderDetailResponse detail(RequestActor actor, UUID orderId) {
        Order order = load(actor, orderId);
        return isKitchen(actor) ? mapper.toKitchenDetail(order) : mapper.toDetail(order);
    }

    public OrderHistoryResponse history(RequestActor actor, UUID orderId) {
        if (actor instanceof RequestActor.Staff(var staff) && !HISTORY_READERS.contains(staff.role())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not view the order history");
        }
        Order order = load(actor, orderId);
        boolean revealActorIds = actor instanceof RequestActor.Staff(var staff) && staff.role() == StaffRole.ADMIN;
        List<OrderHistoryResponse.Entry> entries = history.find(order.getRestaurantId(), order.getId()).stream()
                .map(entry -> new OrderHistoryResponse.Entry(entry.axis().name(), entry.fromStatus(),
                        entry.toStatus(), entry.actorType().name(), entry.actorRole(),
                        revealActorIds ? entry.actorId() : null, entry.source(), entry.reason(), entry.occurredAt()))
                .toList();
        return new OrderHistoryResponse(order.getId(), entries);
    }

    /**
     * Lists orders. Scope is decided by WHO asks, never by the filters: a guest always gets only their own orders of
     * their own table session (a foreign tableSessionId/tableId is refused, not silently ignored); staff get the
     * orders of their own restaurant, optionally narrowed to one table session or one table.
     */
    public OrderListResponse list(RequestActor actor, OrderListQuery query) {
        UUID restaurantId;
        UUID guestSessionId = null;
        UUID tableSessionId = query.tableSessionId();
        switch (actor) {
            case RequestActor.Guest(var context) -> {
                if (!context.isActive()) {
                    throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                            "The table session is no longer active; orders can no longer be viewed");
                }
                if ((query.tableSessionId() != null && !query.tableSessionId().equals(context.tableSessionId()))
                        || (query.tableId() != null && !query.tableId().equals(context.tableId()))) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "You may only list your own orders");
                }
                restaurantId = context.restaurantId();
                guestSessionId = context.guestSessionId();
                tableSessionId = context.tableSessionId();
            }
            case RequestActor.Staff(var staff) -> {
                if (!STAFF_READERS.contains(staff.role())) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not view orders");
                }
                if (staff.role() == StaffRole.KITCHEN && query.paymentStatus() != null) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not filter by payment status");
                }
                restaurantId = staff.restaurantId();
            }
        }
        boolean kitchen = isKitchen(actor);

        Specification<Order> filter = listFilter(restaurantId, guestSessionId, tableSessionId, query);
        // placedAt first, the id as tie-breaker, so a page boundary can never skip or repeat an order
        Sort sort = Sort.by(query.direction(), "placedAt").and(Sort.by(query.direction(), "id"));
        Page<Order> page = orders.findAll(filter, PageRequest.of(query.page(), query.limit(), sort));

        Map<UUID, Long> units = new HashMap<>();
        if (!page.isEmpty()) {
            orders.unitCounts(page.getContent().stream().map(Order::getId).toList())
                    .forEach(count -> units.put(count.getOrderId(), count.getUnits()));
        }
        List<OrderListResponse.Summary> items = page.getContent().stream()
                .map(order -> toListSummary(order, units.getOrDefault(order.getId(), 0L), kitchen))
                .toList();
        return new OrderListResponse(items, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), query.view().name().toLowerCase(Locale.ROOT));
    }

    private static OrderListResponse.Summary toListSummary(Order order, long units, boolean kitchen) {
        return new OrderListResponse.Summary(order.getId(), order.getOrderNo(), order.getTableId(),
                order.getTableSessionId(), order.getFulfillmentStatus().name(),
                kitchen ? null : order.getPaymentStatus().name(), kitchen ? null : order.getCurrency(),
                kitchen ? null : order.getInitialGrossMinor(), kitchen ? null : order.getCurrentDueMinor(),
                units, order.getPlacedAt());
    }

    private static boolean isKitchen(RequestActor actor) {
        return actor instanceof RequestActor.Staff(var staff) && staff.role() == StaffRole.KITCHEN;
    }

    private static Specification<Order> listFilter(UUID restaurantId, UUID guestSessionId, UUID tableSessionId,
                                                    OrderListQuery query) {
        return (root, criteria, builder) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(builder.equal(root.get("restaurantId"), restaurantId));
            if (guestSessionId != null) {
                all.add(builder.equal(root.get("guestSessionId"), guestSessionId));
            }
            if (tableSessionId != null) {
                all.add(builder.equal(root.get("tableSessionId"), tableSessionId));
            }
            if (query.tableId() != null) {
                all.add(builder.equal(root.get("tableId"), query.tableId()));
            }
            all.add(root.get("fulfillmentStatus").in(query.view().statuses()));
            if (query.paymentStatus() != null) {
                all.add(builder.equal(root.get("paymentStatus"), query.paymentStatus()));
            }
            return builder.and(all.toArray(Predicate[]::new));
        };
    }

    private Order load(RequestActor actor, UUID orderId) {
        return switch (actor) {
            case RequestActor.Guest(var guest) -> {
                if (!guest.isActive()) {
                    throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                            "The table session is no longer active; orders can no longer be viewed");
                }
                Order order = find(guest.restaurantId(), orderId);
                if (!order.getGuestSessionId().equals(guest.guestSessionId())) {
                    throw notFound();
                }
                yield order;
            }
            case RequestActor.Staff(var staff) -> {
                if (!STAFF_READERS.contains(staff.role())) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not view orders");
                }
                yield find(staff.restaurantId(), orderId);
            }
        };
    }

    private Order find(UUID restaurantId, UUID orderId) {
        return orders.findByIdAndRestaurantId(orderId, restaurantId).orElseThrow(OrderQueryService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "The order does not exist");
    }
}
