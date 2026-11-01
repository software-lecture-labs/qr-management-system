package com.restofy.order.application.service;

import com.restofy.order.domain.cancellation.CancellationCalculator.ItemCancellation;
import com.restofy.order.domain.cancellation.CancellationCalculator.Result;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.enums.CancellationScope;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.infrastructure.persistence.CancellationStore;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one place where a decided cancellation changes the order, so the immediate path and the Kitchen's later approval
 * do exactly the same thing. It moves the units, what the order still owes, the status of a fully cancelled order and the
 * waiting payment evidence, in the caller's transaction (the order row is already locked there).
 *
 * <p>Money rules (BIGINT minor units, never invented):
 * <ul>
 *   <li>Not paid: the order owes what it owed minus the cancelled gross (0 after a full cancellation). Nothing is
 *       collected, so nothing is refunded.</li>
 *   <li>Paid: the order owes nothing (it was settled); the verified collection stays recorded. A cancellation that makes
 *       the order worth less than what was collected REQUIRES a refund of the difference. That is a request for the
 *       Payment Service / people, never a refund that happened: {@code refunded_minor} stays 0 until the Payment
 *       Service confirms one.</li>
 * </ul>
 */
@Component
public class CancellationApplier {

    static final String SOURCE = "CANCELLATION";

    /** The amounts of one cancellation: what was owed, what is owed now, what was collected, the refund it requires. */
    public record Money(long previousDueMinor, long newDueMinor, long collectedMinor, long refundRequiredMinor) {
    }

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final PaymentCaptureStore captures;
    private final PaymentReconciliationRecorder reconciliation;
    private final CancellationStore cancellations;

    public CancellationApplier(OrderRepository orders, OrderStatusHistoryStore history, PaymentCaptureStore captures,
                               PaymentReconciliationRecorder reconciliation, CancellationStore cancellations) {
        this.orders = orders;
        this.history = history;
        this.captures = captures;
        this.reconciliation = reconciliation;
        this.cancellations = cancellations;
    }

    /** What was verifiably collected for the order so far. */
    public long collected(Order order) {
        return captures.appliedAmount(order.getId());
    }

    /** What this cancellation would mean for the money of the order right now. */
    public Money money(Order order, Result result) {
        long previousDue = order.getCurrentDueMinor();
        long collected = captures.appliedAmount(order.getId());
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            long refundOwedInTotal = Math.max(0L, collected - result.payableAfterMinor());
            long alreadyRequired = cancellations.findByOrder(order.getRestaurantId(), order.getId()).stream()
                    .filter(row -> CancellationStatus.APPLIED.name().equals(row.status()))
                    .mapToLong(CancellationStore.Row::refundRequiredMinor).sum();
            return new Money(previousDue, 0L, collected, Math.max(0L, refundOwedInTotal - alreadyRequired));
        }
        return new Money(previousDue, Math.max(0L, previousDue - result.grossMinor()), collected, 0L);
    }

    /**
     * Applies the cancellation to the (locked) order and writes the history of the status changes it causes.
     *
     * @param actor who is responsible for the change (the requester for an immediate cancellation, the Kitchen service
     *              for a confirmed one)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Order order, Result result, Money money, UUID cancellationId, ChangeActor actor,
                      Instant now, String correlationId) {
        Map<UUID, OrderItem> lines = new HashMap<>();
        order.getItems().forEach(item -> lines.put(item.getId(), item));
        for (ItemCancellation cancelled : result.items()) {
            lines.get(cancelled.orderItemId()).applyCancellation(cancelled.quantity());
        }
        boolean full = result.scope() == CancellationScope.FULL;
        FulfillmentStatus fulfillmentBefore = order.getFulfillmentStatus();
        PaymentStatus paymentBefore = order.getPaymentStatus();
        order.applyCancellation(money.newDueMinor(), full, now);
        boolean closePayment = full && (paymentBefore == PaymentStatus.NOT_REQUESTED
                || paymentBefore == PaymentStatus.PENDING || paymentBefore == PaymentStatus.FAILED);
        if (closePayment) {
            order.closePaymentAxis(now);
        }
        orders.saveAndFlush(order);

        String reason = "cancellationId=" + cancellationId;
        if (full) {
            history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                    OrderStatusHistoryStore.Axis.FULFILLMENT, fulfillmentBefore.name(),
                    FulfillmentStatus.CANCELLED.name(), actor, SOURCE, null, reason, now, correlationId));
        }
        if (closePayment) {
            history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                    OrderStatusHistoryStore.Axis.PAYMENT, paymentBefore.name(), PaymentStatus.CANCELLED.name(),
                    ChangeActor.system(), SOURCE, null, reason, now, correlationId));
        }
        if (full) {
            settleWaitingCaptures(order, now);
        }
    }

    /**
     * A collection that was waiting for its attempt's PENDING can never be verified once the order is cancelled. It is
     * closed as NOT_APPLIED and raised as a reconciliation case, because money may have arrived for a cancelled order.
     */
    private void settleWaitingCaptures(Order order, Instant now) {
        for (PaymentCaptureStore.Capture waiting : captures.pendingVerification(order.getId())) {
            captures.resolve(waiting.captureId(), PaymentCaptureStore.NOT_APPLIED, "ORDER_CANCELLED", now);
            reconciliation.append(order, new PaymentReconciliationRecorder.ReportedCapture(waiting.captureId(),
                    waiting.paymentAttemptId(), waiting.amountMinor(), waiting.currency(), waiting.sourceEventId(),
                    waiting.occurredAt()), "ORDER_CANCELLED", now);
        }
    }
}
