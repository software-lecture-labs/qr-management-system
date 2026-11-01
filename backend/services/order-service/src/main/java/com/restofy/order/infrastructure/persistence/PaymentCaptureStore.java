package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Order Service's local record of the collections the Payment Service reported (see V46). The database, not this
 * class, guarantees that one capture id exists once ({@code UNIQUE(capture_id)}), that an order is settled by at most one
 * collection, and that a capture is never rewritten. Writes must run in the business transaction ({@code MANDATORY}).
 */
@Repository
public class PaymentCaptureStore {

    public static final String APPLIED = "APPLIED";
    public static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    public static final String NOT_APPLIED = "NOT_APPLIED";

    public record Capture(UUID captureId, UUID restaurantId, UUID orderId, UUID paymentAttemptId, long amountMinor,
                          String currency, String status, String notAppliedReason, UUID sourceEventId,
                          Instant occurredAt) {
    }

    public record Case(UUID restaurantId, UUID orderId, UUID captureId, UUID paymentAttemptId, String reason,
                       long reportedAmountMinor, String reportedCurrency, UUID sourceEventId, Instant occurredAt,
                       String fulfillmentStatus, String paymentStatus) {
    }

    private static final String COLUMNS = "capture_id, restaurant_id, order_id, payment_attempt_id, amount_minor, currency,"
            + " status, not_applied_reason, source_event_id, occurred_at";
    private static final String SELECT_CAPTURE = "SELECT " + COLUMNS + " FROM payment_captures";

    private final JdbcTemplate jdbc;

    public PaymentCaptureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Capture> find(UUID captureId) {
        return jdbc.query(SELECT_CAPTURE + " WHERE capture_id = ?",
                (rs, row) -> map(rs), captureId).stream().findFirst();
    }

    /** What was verifiably collected for the order: the sum of its APPLIED captures (0 when none). */
    public long appliedAmount(UUID orderId) {
        Long sum = jdbc.queryForObject("SELECT coalesce(sum(amount_minor), 0) FROM payment_captures"
                + " WHERE order_id = ? AND status = 'APPLIED'", Long.class, orderId);
        return sum == null ? 0L : sum;
    }

    /** The collections of an order that are waiting for the PENDING of the given attempt, oldest first. */
    public List<Capture> pendingVerification(UUID orderId, UUID paymentAttemptId) {
        return jdbc.query(SELECT_CAPTURE + " WHERE order_id = ? AND payment_attempt_id = ?"
                        + " AND status = 'PENDING_VERIFICATION' ORDER BY received_at, capture_id",
                (rs, row) -> map(rs), orderId, paymentAttemptId);
    }

    /** All waiting collections of an order (used when the order is cancelled). */
    public List<Capture> pendingVerification(UUID orderId) {
        return jdbc.query(SELECT_CAPTURE + " WHERE order_id = ?"
                        + " AND status = 'PENDING_VERIFICATION' ORDER BY received_at, capture_id",
                (rs, row) -> map(rs), orderId);
    }

    /**
     * Records a capture if its id is still free. When another transaction claims the same id at the same time this
     * statement waits for it and then reports {@code false}: the caller reads the winner and decides again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(Capture capture, Instant receivedAt) {
        boolean pending = PENDING_VERIFICATION.equals(capture.status());
        return jdbc.update("""
                INSERT INTO payment_captures (id, capture_id, restaurant_id, order_id, payment_attempt_id, amount_minor,
                                              currency, status, not_applied_reason, source_event_id, occurred_at,
                                              received_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (capture_id) DO NOTHING
                """, UUID.randomUUID(), capture.captureId(), capture.restaurantId(), capture.orderId(),
                capture.paymentAttemptId(), capture.amountMinor(), capture.currency(), capture.status(),
                capture.notAppliedReason(), capture.sourceEventId(), Timestamp.from(capture.occurredAt()),
                Timestamp.from(receivedAt), pending ? null : Timestamp.from(receivedAt)) == 1;
    }

    /** A waiting collection becomes APPLIED or NOT_APPLIED, once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(UUID captureId, String status, String notAppliedReason, Instant resolvedAt) {
        int updated = jdbc.update("""
                UPDATE payment_captures SET status = ?, not_applied_reason = ?, resolved_at = ?
                WHERE capture_id = ? AND status = 'PENDING_VERIFICATION'
                """, status, notAppliedReason, Timestamp.from(resolvedAt), captureId);
        if (updated != 1) {
            throw new IllegalStateException("capture " + captureId + " is not waiting for verification");
        }
    }

    /** @return true if this discrepancy is new (the same order/capture/reason is recorded once) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean addCase(Case reconciliationCase, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO payment_reconciliation_cases (id, restaurant_id, order_id, capture_id, payment_attempt_id,
                                                          reason, reported_amount_minor, reported_currency,
                                                          source_event_id, occurred_at, fulfillment_status,
                                                          payment_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id, capture_id, reason) DO NOTHING
                """, UUID.randomUUID(), reconciliationCase.restaurantId(), reconciliationCase.orderId(),
                reconciliationCase.captureId(), reconciliationCase.paymentAttemptId(), reconciliationCase.reason(),
                reconciliationCase.reportedAmountMinor(), reconciliationCase.reportedCurrency(),
                reconciliationCase.sourceEventId(), Timestamp.from(reconciliationCase.occurredAt()),
                reconciliationCase.fulfillmentStatus(), reconciliationCase.paymentStatus(),
                Timestamp.from(createdAt)) == 1;
    }

    private static Capture map(ResultSet rs) throws SQLException {
        return new Capture(rs.getObject("capture_id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("payment_attempt_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("status"),
                rs.getString("not_applied_reason"), rs.getObject("source_event_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
