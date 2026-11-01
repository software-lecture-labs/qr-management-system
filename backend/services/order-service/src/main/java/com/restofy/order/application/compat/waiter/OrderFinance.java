package com.restofy.order.application.compat.waiter;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.enums.CancellationStatus;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;

import java.util.List;

/**
 * The money of one order, with the concepts kept apart, in integer minor units (the Order Service's own representation):
 * the original total (never changes), what the APPLIED cancellations took off it, what the order is worth after them,
 * what was verifiably collected, what is still owed, and the refund that cancellations REQUIRE versus the refund that
 * really HAPPENED. Nothing is invented: a figure that cannot be computed from what is stored is not here, and the sources
 * must agree or the computation fails loudly instead of choosing one.
 */
public final class OrderFinance {

    private OrderFinance() {
    }

    /**
     * @param originalGrossMinor        tax-inclusive total at checkout (orders.initial_gross_minor), never changes
     * @param cancelledGrossMinor       gross of the APPLIED cancellations
     * @param netAfterCancellationsMinor original minus cancelled: what the order is worth now, paid or not
     * @param collectedMinor            verifiably collected (APPLIED payment captures); 0 when nothing was
     * @param outstandingDueMinor       remaining debt (orders.current_due_minor): 0 once a verified payment settled it
     * @param refundRequiredMinor       refund the applied cancellations require (a request, not a refund)
     * @param refundedMinor             refund the Payment Service confirmed (the Order Service never writes it)
     */
    public record Breakdown(String currency, long originalGrossMinor, long cancelledGrossMinor,
                            long netAfterCancellationsMinor, long collectedMinor, long outstandingDueMinor,
                            long refundRequiredMinor, long refundedMinor) {
    }

    /**
     * @throws IllegalStateException if the stored figures contradict each other (the lines do not add up to the original
     *                               total, or the applied cancellations disagree with the cancelled units)
     */
    public static Breakdown compute(Order order, List<Row> cancellations, long collectedMinor) {
        long linesTotal = 0L;
        long cancelledFromLines = 0L;
        for (OrderItem item : order.getItems()) {
            linesTotal = Math.addExact(linesTotal, item.getLineGrossMinor());
            cancelledFromLines = Math.addExact(cancelledFromLines,
                    Math.multiplyExact(item.getUnitGrossMinor(), (long) item.getCancelledQuantity()));
        }
        if (linesTotal != order.getInitialGrossMinor()) {
            throw new IllegalStateException("order " + order.getId() + ": the lines do not add up to the original total");
        }
        long cancelledFromRecords = 0L;
        long refundRequired = 0L;
        long refunded = 0L;
        for (Row row : cancellations) {
            if (CancellationStatus.APPLIED.name().equals(row.status())) {
                cancelledFromRecords = Math.addExact(cancelledFromRecords, row.cancelledGrossMinor());
                refundRequired = Math.addExact(refundRequired, row.refundRequiredMinor());
                refunded = Math.addExact(refunded, row.refundedMinor());
            }
        }
        if (cancelledFromLines != cancelledFromRecords) {
            throw new IllegalStateException("order " + order.getId()
                    + ": the applied cancellations disagree with the cancelled units");
        }
        return new Breakdown(order.getCurrency(), order.getInitialGrossMinor(), cancelledFromRecords,
                order.getInitialGrossMinor() - cancelledFromRecords, collectedMinor, order.getCurrentDueMinor(),
                refundRequired, refunded);
    }
}
