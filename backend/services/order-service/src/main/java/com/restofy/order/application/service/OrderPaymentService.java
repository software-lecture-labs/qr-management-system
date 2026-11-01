package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.PaymentOrderEventRequest;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderPaymentStatusChangedEvent;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.domain.transition.PaymentTransitions;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.config.PaymentProperties;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore.Capture;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies payment statuses reported by the Payment Service. This is the ONLY way the payment status of an order
 * changes: no staff, guest or client request can set it, and "the client showed a success page" is never evidence.
 *
 * <p>Each event is one transaction that locks the order row, so concurrent events of one order are serialised. The
 * payment lifecycle ({@link PaymentTransitions}) is independent of the fulfillment lifecycle: a PAID order may still be
 * in preparation and a COMPLETED order may be unpaid. Fulfillment is never changed here.
 *
 * <p><b>Identity of a payment.</b> The message id ({@code eventId}) only makes a MESSAGE idempotent and is only
 * remembered for the inbox retention window. What identifies the money is the {@code captureId} of a PAID, and what
 * identifies a try is the {@code paymentAttemptId}. Every collection is recorded in {@code payment_captures} (V46), where
 * the DATABASE guarantees that one capture id exists once and that an order is settled by at most one collection, so
 * the same collection can never have a second financial effect, whatever message id it comes under and however long
 * ago the inbox was cleaned. A DIFFERENT collection arriving for an order that is already paid is a possible double
 * charge and becomes a reconciliation case, never a harmless duplicate.
 *
 * <p><b>Money is never lost and never trusted blindly.</b> A PAID is applied only when its attempt is one this order has
 * seen (a PENDING with that attempt id), the order and payment can still take it, and amount and currency equal what
 * the order owes. A PAID that cannot be applied (the order or payment is cancelled, the order is already paid, the
 * attempt had ended, the amount differs, the capture belongs to another order) is recorded as NOT_APPLIED with a
 * reconciliation case and an outbox event, and acknowledged with {@code reconciliationRequired=true}; the statuses stay
 * as they are, and a refund is a decision for people and the Payment Service, never an automatic action here. A PAID
 * that arrives BEFORE the PENDING of its attempt is not guessed at: it waits durably as PENDING_VERIFICATION and is
 * verified and applied (or turned into a case) when that PENDING arrives.
 */
@Slf4j
@Service
public class OrderPaymentService {

    static final String SOURCE_PAYMENT_EVENT = "PAYMENT_EVENT";
    static final String PAYMENT_EVENT_TYPE = "PaymentOrderStatus";
    static final String PENDING_VERIFICATION_EVENT_TYPE = "PaymentPendingVerification";
    private static final String PARAMETER = "parameter";
    private static final String APPLIED = "APPLIED";
    private static final String IGNORED_STALE = "IGNORED_STALE";
    private static final String IGNORED_ORDER_CLOSED = "IGNORED_ORDER_CLOSED";
    private static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final OutboxEventWriter outbox;
    private final InboxStore inbox;
    private final PaymentCaptureStore captures;
    private final PaymentReconciliationRecorder reconciliation;
    private final Clock clock;
    private final PaymentProperties properties;

    public OrderPaymentService(OrderRepository orders, OrderStatusHistoryStore history, OutboxEventWriter outbox,
                               InboxStore inbox, PaymentCaptureStore captures,
                               PaymentReconciliationRecorder reconciliation, Clock clock,
                               PaymentProperties properties) {
        this.orders = orders;
        this.history = history;
        this.outbox = outbox;
        this.inbox = inbox;
        this.captures = captures;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.properties = properties;
    }

    /** What happened to one event; {@code eventType} labels the inbox row of the MESSAGE. */
    private record Decision(String outcome, String eventType) {

        static Decision of(String outcome) {
            return new Decision(outcome, PAYMENT_EVENT_TYPE);
        }

        static Decision reconciled() {
            return new Decision(IGNORED_ORDER_CLOSED, InboxStore.RECONCILIATION_EVENT_TYPE);
        }

        static Decision waiting() {
            return new Decision(PENDING_VERIFICATION, PENDING_VERIFICATION_EVENT_TYPE);
        }
    }

    /** The payment attempts this order has seen, from its own (permanent) history. */
    private record Attempts(Set<UUID> known, UUID latest) {
    }

    /** The payment facts used both when applying a status and when recording a discrepancy. */
    private record PaymentReport(UUID attemptId, UUID captureId, Long amountMinor, String currency,
                                 UUID sourceEventId, Instant occurredAt) {

        static PaymentReport from(PaymentOrderEventRequest event, Instant occurredAt) {
            return new PaymentReport(event.paymentAttemptId(), event.captureId(), event.amountMinor(),
                    event.currency(), event.eventId(), occurredAt);
        }

        static PaymentReport from(Capture capture) {
            return new PaymentReport(capture.paymentAttemptId(), capture.captureId(), capture.amountMinor(),
                    capture.currency(), capture.sourceEventId(), capture.occurredAt());
        }
    }

    /**
     * Order of checks: the caller is the Payment service, the event is well formed (status, references, time within the
     * clock tolerance, amount presence), the order exists in exactly the named restaurant (404), the message id was not
     * processed before (redelivery returns the original outcome), then the decision for the status (see the private
     * methods). For PENDING the amount and currency must equal what the order currently owes (409 PAYMENT_MISMATCH).
     */
    @Transactional
    public OrderEventResponse applyPaymentEvent(ServiceActor service, PaymentOrderEventRequest event) {
        if (service.name() != ServiceName.PAYMENT) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the Payment service may send these events");
        }
        PaymentStatus target = parse(event.paymentStatus());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant eventAt = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
        validate(event, target, now, eventAt);

        Order order = orders.findForUpdate(event.orderId(), event.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        var processed = inbox.find(InboxStore.PAYMENT, event.eventId());
        if (processed.isPresent()) {
            if (!processed.get().orderId().equals(order.getId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This event id was already used for a different order");
            }
            return redelivery(order, event, processed.get());
        }

        Decision decision = target == PaymentStatus.PAID ? decidePaid(order, event, eventAt, now)
                : decideOther(order, target, event, eventAt, now);
        inbox.append(InboxStore.PAYMENT, event.eventId(), order.getRestaurantId(), order.getId(), decision.eventType(),
                decision.outcome(), now);
        return response(decision.outcome(), false, order, InboxStore.RECONCILIATION_EVENT_TYPE.equals(decision.eventType()));
    }

    /** A message seen before answers as it did, except a waiting PAID, whose capture may have been resolved since. */
    private OrderEventResponse redelivery(Order order, PaymentOrderEventRequest event, InboxStore.Processed processed) {
        if (PENDING_VERIFICATION_EVENT_TYPE.equals(processed.eventType()) && event.captureId() != null) {
            var capture = captures.find(event.captureId());
            if (capture.isPresent()) {
                return switch (capture.get().status()) {
                    case PaymentCaptureStore.APPLIED -> response(APPLIED, true, order, false);
                    case PaymentCaptureStore.NOT_APPLIED -> response(IGNORED_ORDER_CLOSED, true, order, true);
                    default -> response(PENDING_VERIFICATION, true, order, false);
                };
            }
        }
        return response(processed.outcome(), true, order,
                InboxStore.RECONCILIATION_EVENT_TYPE.equals(processed.eventType()));
    }

    // ---- PAID -----------------------------------------------------------------------------------------------------

    /**
     * PAID. The capture id decides first (it is the identity of the money), then the state of the order and payment.
     * A PAID is never ignored because of its timestamp.
     */
    private Decision decidePaid(Order order, PaymentOrderEventRequest event, Instant eventAt, Instant now) {
        var known = captures.find(event.captureId());
        if (known.isPresent()) {
            return onKnownCapture(order, event, known.get(), now);
        }
        PaymentStatus current = order.getPaymentStatus();
        if (order.getFulfillmentStatus() == FulfillmentStatus.CANCELLED) {
            return notApplied(order, event, "ORDER_CANCELLED", eventAt, now);
        }
        if (current == PaymentStatus.PAID) {
            return notApplied(order, event, "SECOND_CAPTURE", eventAt, now);
        }
        if (current == PaymentStatus.CANCELLED) {
            return notApplied(order, event, "PAYMENT_CANCELLED", eventAt, now);
        }
        if (!attempts(order).known().contains(event.paymentAttemptId())) {
            // The PENDING of this attempt has not arrived: the PAID is neither trusted nor dropped, it waits durably.
            return claimed(order, event, PaymentCaptureStore.PENDING_VERIFICATION, null, eventAt, now)
                    .orElseGet(Decision::waiting);
        }
        if (current != PaymentStatus.PENDING) {
            return notApplied(order, event, "ATTEMPT_ENDED", eventAt, now);   // the attempt had already failed
        }
        if (!amountMatches(order, event.amountMinor(), event.currency())) {
            return notApplied(order, event, "AMOUNT_MISMATCH", eventAt, now);
        }
        return claimed(order, event, PaymentCaptureStore.APPLIED, null, eventAt, now).orElseGet(() -> {
            applyStatus(order, PaymentStatus.PAID, PaymentReport.from(event, eventAt), now);
            return Decision.of(APPLIED);
        });
    }

    /**
     * Records the capture in the given status. Empty when this transaction claimed the id; otherwise another claimed it
     * first (concurrently) and the answer is what that capture means for this order.
     */
    private Optional<Decision> claimed(Order order, PaymentOrderEventRequest event, String status,
                                                 String reason, Instant eventAt, Instant now) {
        Capture row = new Capture(event.captureId(), order.getRestaurantId(), order.getId(), event.paymentAttemptId(),
                event.amountMinor(), event.currency().toUpperCase(Locale.ROOT), status, reason, event.eventId(),
                eventAt);
        if (captures.claim(row, now)) {
            return Optional.empty();
        }
        return Optional.of(onKnownCapture(order, event, captures.find(event.captureId()).orElseThrow(), now));
    }

    private Decision notApplied(Order order, PaymentOrderEventRequest event, String reason, Instant eventAt, Instant now) {
        return claimed(order, event, PaymentCaptureStore.NOT_APPLIED, reason, eventAt, now).orElseGet(() -> {
            recordCase(order, PaymentReport.from(event, event.occurredAt()), reason, now);
            return Decision.reconciled();
        });
    }

    /** The capture id is already known: this PAID is a repeat, an answer about a waiting one, or somebody else's money. */
    private Decision onKnownCapture(Order order, PaymentOrderEventRequest event, Capture capture, Instant now) {
        if (!capture.orderId().equals(order.getId())) {
            recordCase(order, PaymentReport.from(event, event.occurredAt()), "CAPTURE_USED_ON_OTHER_ORDER", now);
            return Decision.reconciled();
        }
        return switch (capture.status()) {
            case PaymentCaptureStore.APPLIED -> Decision.of(IGNORED_STALE);
            case PaymentCaptureStore.NOT_APPLIED -> Decision.reconciled();
            default -> Decision.waiting();
        };
    }

    /** Raises a reconciliation case once per (order, capture, reason); see {@link PaymentReconciliationRecorder}. */
    private void recordCase(Order order, PaymentReport report, String reason, Instant now) {
        reconciliation.append(order, new PaymentReconciliationRecorder.ReportedCapture(report.captureId(),
                report.attemptId(), report.amountMinor(), report.currency(), report.sourceEventId(), report.occurredAt()),
                reason, now);
    }

    // ---- PENDING, FAILED, CANCELLED ---------------------------------------------------------------------------------

    /** PENDING, FAILED and CANCELLED: attempt based, then state machine, then (not for PAID) the event time. */
    private Decision decideOther(Order order, PaymentStatus target, PaymentOrderEventRequest event, Instant eventAt,
                                 Instant now) {
        PaymentStatus current = order.getPaymentStatus();
        boolean orderCancelled = order.getFulfillmentStatus() == FulfillmentStatus.CANCELLED;
        UUID attempt = event.paymentAttemptId();
        if (target == PaymentStatus.PENDING && orderCancelled) {
            return Decision.of(IGNORED_ORDER_CLOSED);
        }
        if (isRepeatedOrLateAttempt(order, target, attempt)) {
            return Decision.of(IGNORED_STALE);
        }
        if (PaymentTransitions.isStale(current, target, eventAt, order.getPaymentEventAt())) {
            return Decision.of(IGNORED_STALE);
        }
        if (PaymentTransitions.isAllowed(current, target, orderCancelled)) {
            if (PaymentTransitions.AMOUNT_CHECKED.contains(target)
                    && !amountMatches(order, event.amountMinor(), event.currency())) {
                throw new BusinessException(ErrorCode.PAYMENT_MISMATCH,
                        "The reported amount or currency does not match what the order owes");
            }
            applyStatus(order, target, PaymentReport.from(event, eventAt), now);
            if (target == PaymentStatus.PENDING) {
                resolveWaitingCaptures(order, attempt, now);
            }
            return Decision.of(APPLIED);
        }
        if (orderCancelled) {
            return Decision.of(IGNORED_ORDER_CLOSED);
        }
        throw invalidTransition(current, target);
    }

    /** Validates attempt identity before state/time checks; old attempts cannot end a newer one. */
    private boolean isRepeatedOrLateAttempt(Order order, PaymentStatus target, UUID attempt) {
        PaymentStatus current = order.getPaymentStatus();
        Attempts attempts = attempts(order);
        if (target == PaymentStatus.PENDING) {
            if (attempts.known().contains(attempt)) {
                return true;
            }
            // A different attempt starts only after Payment has ended the current one.
            if (current == PaymentStatus.PENDING) {
                throw invalidTransition(current, target);
            }
        } else if (current == PaymentStatus.PENDING || current == PaymentStatus.FAILED) {
            if (!attempts.known().contains(attempt)) {
                throw unknownAttempt();
            }
            return !attempt.equals(attempts.latest());
        }
        return false;
    }

    /**
     * The PENDING of an attempt has arrived: the PAIDs that were waiting for it are verified now. The first whose amount
     * and currency match settles the order; a waiting PAID that does not match, and any further one, becomes a case.
     */
    private void resolveWaitingCaptures(Order order, UUID attempt, Instant now) {
        boolean settled = false;
        for (Capture waiting : captures.pendingVerification(order.getId(), attempt)) {
            if (!settled && order.getPaymentStatus() == PaymentStatus.PENDING
                    && amountMatches(order, waiting.amountMinor(), waiting.currency())) {
                captures.resolve(waiting.captureId(), PaymentCaptureStore.APPLIED, null, now);
                applyStatus(order, PaymentStatus.PAID, PaymentReport.from(waiting), now);
                settled = true;
            } else {
                String reason = settled ? "SECOND_CAPTURE" : "AMOUNT_MISMATCH";
                captures.resolve(waiting.captureId(), PaymentCaptureStore.NOT_APPLIED, reason, now);
                recordCase(order, PaymentReport.from(waiting), reason, now);
            }
        }
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    private Attempts attempts(Order order) {
        Set<UUID> known = new LinkedHashSet<>();
        UUID latest = null;
        for (var entry : history.find(order.getRestaurantId(), order.getId())) {
            if (entry.axis() == OrderStatusHistoryStore.Axis.PAYMENT && PaymentStatus.PENDING.name().equals(entry.toStatus())) {
                UUID attempt = PaymentReferences.attemptOf(entry.reason());
                if (attempt != null) {
                    known.add(attempt);
                    latest = attempt;
                }
            }
        }
        return new Attempts(known, latest);
    }

    private void applyStatus(Order order, PaymentStatus target, PaymentReport report, Instant now) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        PaymentStatus from = order.getPaymentStatus();
        Long amountMinor = PaymentTransitions.AMOUNT_CHECKED.contains(target) ? report.amountMinor() : null;
        order.changePaymentStatus(target, report.occurredAt(), now);
        orders.saveAndFlush(order);
        ChangeActor actor = ChangeActor.service(ServiceName.PAYMENT.name());
        // the references and the verified amount are part of the permanent audit trail of this change
        String reason = PaymentReferences.reason(report.attemptId(), report.captureId(), amountMinor, order.getCurrency());
        history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                OrderStatusHistoryStore.Axis.PAYMENT, from.name(), target.name(), actor, SOURCE_PAYMENT_EVENT,
                report.sourceEventId(), reason, now, correlationId));
        UUID eventId = UUID.randomUUID();
        var changed = new OrderPaymentStatusChangedEvent(eventId, OrderPaymentStatusChangedEvent.TYPE,
                OrderPaymentStatusChangedEvent.SCHEMA_VERSION, now, correlationId, order.getRestaurantId(),
                order.getTableId(), order.getTableSessionId(), order.getId(), order.getOrderNo(), from.name(),
                target.name(), order.getFulfillmentStatus().name(), order.getCurrency(), amountMinor,
                order.getCurrentDueMinor(), report.attemptId(), report.captureId(), SOURCE_PAYMENT_EVENT);
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), changed.eventType(),
                changed.schemaVersion(), correlationId, changed, now, destinations(target));
    }

    /** Payment itself caused the change, so it is not echoed; QR also learns about PAID (a table-closing signal). */
    private static List<EventDestination> destinations(PaymentStatus target) {
        List<EventDestination> destinations = new ArrayList<>(List.of(EventDestination.NOTIFICATION,
                EventDestination.WAITER));
        if (target == PaymentStatus.PAID) {
            destinations.add(EventDestination.QR);
        }
        return destinations;
    }

    private void validate(PaymentOrderEventRequest event, PaymentStatus target, Instant now, Instant eventAt) {
        if (eventAt.isAfter(now.plus(properties.maxClockSkew()))) {
            throw invalid("occurredAt", "must not be in the future");
        }
        if (PaymentTransitions.AMOUNT_CHECKED.contains(target)
                && (event.amountMinor() == null || event.currency() == null)) {
            throw invalid("amountMinor", "amountMinor and currency are required for " + target);
        }
        if (target == PaymentStatus.PAID && event.captureId() == null) {
            throw invalid("captureId", "captureId (the real collection) is required for PAID");
        }
        if (target != PaymentStatus.PAID && event.captureId() != null) {
            throw invalid("captureId", "captureId is only allowed on PAID; use paymentAttemptId for the attempt");
        }
    }

    private static boolean amountMatches(Order order, long amountMinor, String currency) {
        return amountMinor == order.getCurrentDueMinor() && order.getCurrency().equals(currency.toUpperCase(Locale.ROOT));
    }

    private static BusinessException unknownAttempt() {
        return new BusinessException(ErrorCode.PAYMENT_MISMATCH,
                "The payment attempt is not one this order has seen", Map.of("reason", "UNKNOWN_PAYMENT_ATTEMPT"));
    }

    private static BusinessException invalidTransition(PaymentStatus current, PaymentStatus target) {
        return new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                "The payment cannot move from " + current + " to " + target,
                Map.of("currentStatus", current.name(), "requestedStatus", target.name(), "allowedNext",
                        PaymentTransitions.allowedNext(current).stream().map(Enum::name).sorted().toList()));
    }

    private static PaymentStatus parse(String value) {
        try {
            PaymentStatus status = PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (PaymentTransitions.REPORTABLE.contains(status)) {
                return status;
            }
        } catch (IllegalArgumentException _) {
            // falls through to the validation error below
        }
        throw invalid("paymentStatus", "must be one of PENDING, PAID, FAILED, CANCELLED");
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid payment event",
                Map.of(PARAMETER, parameter, "rule", rule));
    }

    private static OrderEventResponse response(String outcome, boolean duplicate, Order order,
                                               boolean reconciliationRequired) {
        return new OrderEventResponse(outcome, duplicate, order.getId(), order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(), reconciliationRequired);
    }
}
