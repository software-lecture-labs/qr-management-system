package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.KitchenOrderEventRequest;
import com.restofy.order.api.dto.response.FulfillmentStatusResponse;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderFulfillmentStatusChangedEvent;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.domain.transition.FulfillmentTransitions;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Moves an order along its fulfillment lifecycle, from exactly two kinds of caller: authenticated staff (the PATCH
 * endpoint) and the authenticated Kitchen service (internal events). Every change is one transaction that locks the
 * order row, checks the state machine and the caller's authority, updates the order, appends the history row and writes
 * the outbox event, so concurrent callers are serialised and all-or-nothing. A guest can never reach this service.
 */
@Service
public class OrderFulfillmentService {

    static final String SOURCE_STAFF = "STAFF_API";
    static final String SOURCE_KITCHEN_EVENT = "KITCHEN_EVENT";
    static final String KITCHEN_EVENT_TYPE = "KitchenOrderStatus";
    private static final String PARAMETER = "parameter";

    /** Kitchen V1 statuses and the Order status each one means. */
    private static final Map<String, FulfillmentStatus> KITCHEN_STATUS = Map.of(
            "WAITING", FulfillmentStatus.RECEIVED,
            "PREPARING", FulfillmentStatus.PREPARING,
            "READY", FulfillmentStatus.READY);

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final OutboxEventWriter outbox;
    private final InboxStore inbox;
    private final Clock clock;
    private final FulfillmentAuthorityPolicy authority;

    public OrderFulfillmentService(OrderRepository orders, OrderStatusHistoryStore history, OutboxEventWriter outbox,
                                   InboxStore inbox, Clock clock, FulfillmentAuthorityPolicy authority) {
        this.authority = authority;
        this.orders = orders;
        this.history = history;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
    }

    /**
     * Staff moves an order one step. Order of checks: the requested status is valid, the role may set it (403), the
     * order exists in the caller's own restaurant (404, identical for a foreign order), the step is the next one (409).
     * If the order is already in the requested status the call is a harmless no-op for ANY authorised caller (no second
     * history row, no second event): who reports an already-reached status does not matter, only that it is reached.
     * A status behind the current one is never applied (409), so a late call cannot move an order backwards.
     */
    @Transactional
    public FulfillmentStatusResponse changeByStaff(StaffActor staff, UUID orderId, String requestedStatus,
                                                   String reason) {
        FulfillmentStatus target = parse(requestedStatus);
        if (target == FulfillmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "An order is cancelled through the cancellation process, not by changing its status");
        }
        if (!FulfillmentTransitions.mayStaffSet(staff.role(), target)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not set that status");
        }
        if (staff.role() == StaffRole.KITCHEN && !authority.kitchenStaffMaySetPreparationStatuses()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "In this environment PREPARING and READY are set only by the Kitchen Service");
        }
        Order order = lock(staff.restaurantId(), orderId);
        FulfillmentStatus current = order.getFulfillmentStatus();
        if (current == target) {
            return response(order, current, false);
        }
        if (!FulfillmentTransitions.isNextStep(current, target)) {
            throw invalidTransition(current, target);
        }
        String cleanReason = cleanReason(reason);
        boolean byKitchen = staff.role() == StaffRole.KITCHEN;
        apply(order, target, ChangeActor.staff(staff), SOURCE_STAFF, null, cleanReason, byKitchen);
        return response(order, current, true);
    }

    /**
     * The Kitchen service reports a preparation status. The event is recognised by its id (a redelivery returns the
     * original outcome), a late or repeated status is acknowledged without effect, and a status that would skip a step
     * is refused with 409 so the Kitchen retries after the missing step arrived.
     */
    @Transactional
    public OrderEventResponse applyKitchenEvent(ServiceActor service, KitchenOrderEventRequest event) {
        FulfillmentStatus target = KITCHEN_STATUS.get(event.kitchenStatus().trim().toUpperCase(Locale.ROOT));
        if (target == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown kitchen status",
                    Map.of(PARAMETER, "kitchenStatus", "rule", "must be one of WAITING, PREPARING, READY"));
        }
        if (service.name() != ServiceName.KITCHEN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the Kitchen service may send these events");
        }
        // the order must exist in exactly the restaurant the event names; anything else looks like a missing order
        Order order = lock(event.restaurantId(), event.orderId());
        var processed = inbox.find(InboxStore.KITCHEN, event.eventId());
        if (processed.isPresent()) {
            if (!processed.get().orderId().equals(order.getId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This event id was already used for a different order");
            }
            return new OrderEventResponse(processed.get().outcome(), true, order.getId(),
                    order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), false);
        }
        FulfillmentStatus current = order.getFulfillmentStatus();
        String outcome;
        if (current == FulfillmentStatus.CANCELLED) {
            outcome = "IGNORED_ORDER_CLOSED";
        } else if (FulfillmentTransitions.isAtOrBehind(current, target)) {
            outcome = "IGNORED_STALE";
        } else if (FulfillmentTransitions.isNextStep(current, target)) {
            apply(order, target, ChangeActor.service(service.name().name()), SOURCE_KITCHEN_EVENT, event.eventId(),
                    null, true);
            outcome = "APPLIED";
        } else {
            throw invalidTransition(current, target);
        }
        inbox.append(InboxStore.KITCHEN, event.eventId(), order.getRestaurantId(), order.getId(), KITCHEN_EVENT_TYPE,
                outcome, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return new OrderEventResponse(outcome, false, order.getId(), order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(), false);
    }

    private void apply(Order order, FulfillmentStatus target, ChangeActor actor, String source, UUID sourceEventId,
                       String reason, boolean originatedByKitchen) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        FulfillmentStatus from = order.getFulfillmentStatus();
        order.changeFulfillmentStatus(target, now);
        orders.saveAndFlush(order);
        history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                OrderStatusHistoryStore.Axis.FULFILLMENT, from.name(), target.name(), actor, source, sourceEventId,
                reason, now, correlationId));
        UUID eventId = UUID.randomUUID();
        var event = new OrderFulfillmentStatusChangedEvent(eventId, OrderFulfillmentStatusChangedEvent.TYPE,
                OrderFulfillmentStatusChangedEvent.SCHEMA_VERSION, now, correlationId, order.getRestaurantId(),
                order.getTableId(), order.getTableSessionId(), order.getId(), order.getOrderNo(), from.name(),
                target.name(), actor.type().name(), actor.role(), source);
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), event.eventType(),
                event.schemaVersion(), correlationId, event, now, destinations(target, originatedByKitchen));
    }

    /** Who needs to hear about a status: everyone but the Kitchen when the Kitchen itself caused it. */
    private static List<EventDestination> destinations(FulfillmentStatus target, boolean originatedByKitchen) {
        List<EventDestination> destinations = new ArrayList<>(List.of(EventDestination.NOTIFICATION,
                EventDestination.WAITER));
        if (!originatedByKitchen) {
            destinations.add(EventDestination.KITCHEN);
        }
        if (target == FulfillmentStatus.COMPLETED) {
            destinations.add(EventDestination.QR);
        }
        return destinations;
    }

    /** Locks the order row so concurrent changes of one order run one after the other. */
    private Order lock(UUID restaurantId, UUID orderId) {
        return orders.findForUpdate(orderId, restaurantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
    }

    private static FulfillmentStatus parse(String value) {
        try {
            return FulfillmentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown fulfillment status",
                    Map.of(PARAMETER, "status", "rule",
                            "must be one of RECEIVED, PREPARING, READY, SERVED, COMPLETED"));
        }
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The reason contains control characters",
                    Map.of(PARAMETER, "reason", "rule", "must be plain text"));
        }
        return trimmed;
    }

    private static BusinessException invalidTransition(FulfillmentStatus current, FulfillmentStatus target) {
        return new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                "The order cannot move from " + current + " to " + target,
                Map.of("currentStatus", current.name(), "requestedStatus", target.name(),
                        "allowedNext", FulfillmentTransitions.next(current).map(Enum::name).orElse("NONE")));
    }

    private static FulfillmentStatusResponse response(Order order, FulfillmentStatus previous, boolean changed) {
        return new FulfillmentStatusResponse(order.getId(), order.getOrderNo(), previous.name(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), changed, order.getUpdatedAt());
    }
}
