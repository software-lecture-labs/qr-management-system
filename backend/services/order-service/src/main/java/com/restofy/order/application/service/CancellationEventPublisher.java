package com.restofy.order.application.service;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.CancellationScope;
import com.restofy.order.domain.event.CancellationEventParts.Actor;
import com.restofy.order.domain.event.CancellationEventParts.Item;
import com.restofy.order.domain.event.CancellationEventParts.Reason;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderAmountAdjustedEvent;
import com.restofy.order.domain.event.OrderCancelledEvent;
import com.restofy.order.domain.event.OrderCancellationRejectedEvent;
import com.restofy.order.domain.event.OrderCancellationRequestedEvent;
import com.restofy.order.domain.event.OrderItemsCancelledEvent;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.ReasonRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the cancellation events into the transactional outbox, in the SAME transaction as the cancellation they
 * describe, so an event exists if and only if the change was committed. Recording an event is not delivering it: there is
 * no dispatcher yet, every delivery waits as PENDING and nothing claims that another service has received anything.
 *
 * <p>Destinations: a request goes to the Kitchen (to decide), Waiter, Admin and Notification; a whole-order cancellation
 * to everybody who has to stop or account for the order (Kitchen, Payment, Waiter, Admin, Notification, QR); a partial
 * one to Kitchen, Waiter, Admin and Notification; the amount adjustment to Payment and Admin; a rejection to Waiter,
 * Admin and Notification (and the Kitchen unless the Kitchen itself rejected it).
 */
@Component
public class CancellationEventPublisher {

    private static final String AGGREGATE = "ORDER";

    public record ApplicationSnapshot(String previousFulfillmentStatus, long originalGrossMinor,
                                      long cancelledTotalMinor, boolean originatedByKitchen) {
        public ApplicationSnapshot(String previousFulfillmentStatus, long originalGrossMinor, long cancelledTotalMinor) {
            this(previousFulfillmentStatus, originalGrossMinor, cancelledTotalMinor, false);
        }
    }

    private final OutboxEventWriter outbox;

    public CancellationEventPublisher(OutboxEventWriter outbox) {
        this.outbox = outbox;
    }

    /** @param remainingAfter units of each line that would still be active if the request is applied */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requested(Order order, Row row, List<ItemRow> items, List<ReasonRow> reasons,
                          Map<UUID, Integer> remainingAfter, Instant now) {
        UUID id = UUID.randomUUID();
        var event = new OrderCancellationRequestedEvent(id, OrderCancellationRequestedEvent.TYPE,
                OrderCancellationRequestedEvent.SCHEMA_VERSION, now, correlation(), order.getRestaurantId(),
                order.getId(), order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), row.id(), row.scope(),
                row.kitchenConfirmationRequired(), items(items, remainingAfter), reasons(reasons), requester(row),
                row.previousDueMinor(), row.newDueMinor(), row.currency());
        write(order, id, event.eventType(), event.schemaVersion(), event, now,
                List.of(EventDestination.KITCHEN, EventDestination.WAITER, EventDestination.ADMIN,
                        EventDestination.NOTIFICATION));
    }

    /**
     * The cancellation was applied: {@code OrderCancelled} for the whole order or {@code OrderItemsCancelled} for part
     * of it, and {@code OrderAmountAdjusted} when what the order owes changed or a refund is required.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applied(Order order, Row row, List<ItemRow> items, List<ReasonRow> reasons,
                        Map<UUID, Integer> remainingAfter, ApplicationSnapshot snapshot, Instant now) {
        String previousFulfillmentStatus = snapshot.previousFulfillmentStatus();
        long originalGrossMinor = snapshot.originalGrossMinor();
        long cancelledTotalMinor = snapshot.cancelledTotalMinor();
        boolean full = CancellationScope.FULL.name().equals(row.scope());
        UUID id = UUID.randomUUID();
        if (full) {
            var event = new OrderCancelledEvent(id, OrderCancelledEvent.TYPE, OrderCancelledEvent.SCHEMA_VERSION, now,
                    correlation(), order.getRestaurantId(), order.getId(), order.getOrderNo(), order.getTableId(),
                    order.getTableSessionId(), order.getFulfillmentStatus().name(), order.getPaymentStatus().name(),
                    row.id(), items(items, remainingAfter), reasons(reasons), requester(row), decider(row),
                    row.decidedAt(), previousFulfillmentStatus, row.previousDueMinor(), row.newDueMinor(),
                    row.cancelledGrossMinor(), row.currency(), row.refundRequiredMinor(),
                    row.refundRequiredMinor() > 0);
            write(order, id, event.eventType(), event.schemaVersion(), event, now,
                    without(snapshot.originatedByKitchen(), EventDestination.PAYMENT, EventDestination.WAITER,
                            EventDestination.ADMIN, EventDestination.NOTIFICATION, EventDestination.QR));
        } else {
            var event = new OrderItemsCancelledEvent(id, OrderItemsCancelledEvent.TYPE,
                    OrderItemsCancelledEvent.SCHEMA_VERSION, now, correlation(), order.getRestaurantId(), order.getId(),
                    order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                    order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), row.id(),
                    items(items, remainingAfter), reasons(reasons), requester(row), decider(row), row.decidedAt(),
                    row.previousDueMinor(), row.newDueMinor(), row.cancelledGrossMinor(), row.currency(),
                    row.refundRequiredMinor());
            write(order, id, event.eventType(), event.schemaVersion(), event, now,
                    without(snapshot.originatedByKitchen(), EventDestination.WAITER, EventDestination.ADMIN,
                            EventDestination.NOTIFICATION));
        }
        if (row.newDueMinor() != row.previousDueMinor() || row.refundRequiredMinor() > 0) {
            UUID adjustedId = UUID.randomUUID();
            var adjusted = new OrderAmountAdjustedEvent(adjustedId, OrderAmountAdjustedEvent.TYPE,
                    OrderAmountAdjustedEvent.SCHEMA_VERSION, now, correlation(), order.getRestaurantId(), order.getId(),
                    order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                    order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), row.id(),
                    full ? "FULL_CANCELLATION" : "PARTIAL_CANCELLATION", row.previousDueMinor(), row.newDueMinor(),
                    originalGrossMinor, cancelledTotalMinor, row.currency(), order.getPaymentStatus().name(),
                    row.refundRequiredMinor());
            write(order, adjustedId, adjusted.eventType(), adjusted.schemaVersion(), adjusted, now,
                    List.of(EventDestination.PAYMENT, EventDestination.ADMIN));
        }
    }

    /** @param rejectedByKitchen the Kitchen itself rejected it, so it is not told about its own answer */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(Order order, Row row, List<ItemRow> items, Map<UUID, Integer> remainingAfter,
                         boolean rejectedByKitchen, Instant now) {
        UUID id = UUID.randomUUID();
        var event = new OrderCancellationRejectedEvent(id, OrderCancellationRejectedEvent.TYPE,
                OrderCancellationRejectedEvent.SCHEMA_VERSION, now, correlation(), order.getRestaurantId(),
                order.getId(), order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), row.id(), row.scope(),
                items(items, remainingAfter), requester(row), decider(row), row.rejectionCode(), row.rejectionNote(),
                row.decidedAt());
        List<EventDestination> destinations = new ArrayList<>(List.of(EventDestination.WAITER, EventDestination.ADMIN,
                EventDestination.NOTIFICATION));
        if (!rejectedByKitchen) {
            destinations.add(EventDestination.KITCHEN);
        }
        write(order, id, event.eventType(), event.schemaVersion(), event, now, destinations);
    }

    /** The usual destinations, with the Kitchen first unless it is the one that asked. */
    private static List<EventDestination> without(boolean originatedByKitchen, EventDestination... others) {
        List<EventDestination> all = new ArrayList<>();
        if (!originatedByKitchen) {
            all.add(EventDestination.KITCHEN);
        }
        all.addAll(List.of(others));
        return all;
    }

    private void write(Order order, UUID eventId, String type, int schemaVersion, Object payload, Instant now,
                       List<EventDestination> destinations) {
        outbox.write(eventId, order.getRestaurantId(), AGGREGATE, order.getId(), order.getVersion(), type,
                schemaVersion, correlation(), payload, now, destinations);
    }

    private static String correlation() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    private static List<Item> items(List<ItemRow> items, Map<UUID, Integer> remainingAfter) {
        return items.stream().map(item -> new Item(item.orderItemId(), item.productId(), item.productName(),
                item.quantity(), remainingAfter.getOrDefault(item.orderItemId(), 0), item.grossMinor())).toList();
    }

    private static List<Reason> reasons(List<ReasonRow> reasons) {
        return reasons.stream().map(reason -> new Reason(reason.source(), reason.code(), reason.text())).toList();
    }

    private static Actor requester(Row row) {
        return new Actor(row.requestedByType(), row.requestedByRole());
    }

    private static Actor decider(Row row) {
        return new Actor(row.decidedByType(), row.decidedByRole());
    }
}
