package com.restofy.order.application.service;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderPaymentReconciliationRequiredEvent;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Raises a payment reconciliation case: money the Payment Service reported that could not be applied to the order. It
 * is shared by the payment events and by the cancellation of an order that still had a collection waiting for
 * verification. A case is recorded once per (order, capture, reason); a new one is also announced by an outbox event
 * (Admin, Payment, Notification). The refund itself is for people and the Payment Service to decide.
 */
@Slf4j
@Component
public class PaymentReconciliationRecorder {

    public record ReportedCapture(UUID captureId, UUID attemptId, long amountMinor, String currency,
                                  UUID sourceEventId, Instant occurredAt) {
    }

    private final PaymentCaptureStore captures;
    private final OutboxEventWriter outbox;

    public PaymentReconciliationRecorder(PaymentCaptureStore captures, OutboxEventWriter outbox) {
        this.captures = captures;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Order order, ReportedCapture report, String reason, Instant now) {
        UUID captureId = report.captureId();
        UUID attemptId = report.attemptId();
        long amountMinor = report.amountMinor();
        UUID sourceEventId = report.sourceEventId();
        Instant occurredAt = report.occurredAt();
        String normalizedCurrency = report.currency().toUpperCase(Locale.ROOT);
        var reconciliationCase = new PaymentCaptureStore.Case(order.getRestaurantId(), order.getId(), captureId,
                attemptId, reason, amountMinor, normalizedCurrency, sourceEventId,
                occurredAt.truncatedTo(ChronoUnit.MICROS), order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name());
        if (!captures.addCase(reconciliationCase, now)) {
            return;
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        UUID eventId = UUID.randomUUID();
        var required = new OrderPaymentReconciliationRequiredEvent(eventId,
                OrderPaymentReconciliationRequiredEvent.TYPE, OrderPaymentReconciliationRequiredEvent.SCHEMA_VERSION,
                now, correlationId, order.getRestaurantId(), order.getTableId(), order.getTableSessionId(),
                order.getId(), order.getOrderNo(), reason, sourceEventId, attemptId, captureId, amountMinor,
                normalizedCurrency, occurredAt, order.getFulfillmentStatus().name(), order.getPaymentStatus().name(),
                order.getCurrentDueMinor());
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), required.eventType(),
                required.schemaVersion(), correlationId, required, now,
                List.of(EventDestination.ADMIN, EventDestination.PAYMENT, EventDestination.NOTIFICATION));
        log.warn("Payment reported PAID that cannot be applied, reconciliation required: orderId={} reason={}"
                + " paymentEventId={} captureId={}", order.getId(), reason, sourceEventId, captureId);
    }
}
