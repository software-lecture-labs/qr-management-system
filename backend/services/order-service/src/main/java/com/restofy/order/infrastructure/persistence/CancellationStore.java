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

import static com.restofy.order.infrastructure.persistence.JdbcTimestamps.timestamp;

/**
 * Persistence of order cancellations (V47). Writes must run in the business transaction ({@code MANDATORY}) and the
 * database enforces the rules that must hold whatever the code does: one open request per order, a decision made once,
 * append-only lines and reasons, and {@code order_items.cancelled_quantity} equal to the applied cancellations.
 */
@Repository
public class CancellationStore {

    /**
     * One cancellation. {@code requestedByType} is the true kind of the requester: GUEST, STAFF (a verified staff member)
     * or SERVICE (a verified calling service; only the Kitchen service). {@code reportedStaffId} is the staff member a
     * SERVICE says it acted for: a claim recorded once with the request, never an author, never used to authorise.
     */
    public record Row(UUID id, UUID restaurantId, UUID orderId, String scope, String status, String requestedByType,
                      String requestedById, String requestedByRole, UUID reportedStaffId, Instant requestedAt,
                      boolean kitchenConfirmationRequired, String fulfillmentStatusAtRequest,
                      String paymentStatusAtRequest, String decisionSource, String decidedByType, String decidedById,
                      String decidedByRole, Instant decidedAt, String rejectionCode, String rejectionNote,
                      String currency, long previousDueMinor, long cancelledGrossMinor, long cancelledNetMinor,
                      long cancelledVatMinor, long newDueMinor, long collectedMinor, long refundRequiredMinor,
                      long refundedMinor, Instant staffReasonAddedAt, String staffReasonAddedBy, long version,
                      Instant createdAt) {
    }

    public record ItemRow(UUID orderItemId, UUID productId, String productName, int quantity, long unitGrossMinor,
                          int taxRateBps, long grossMinor, long netMinor, long vatMinor) {
    }

    /** One reason. {@code source} and {@code addedByType} are the true kind of the author: GUEST, STAFF or SERVICE. */
    public record ReasonRow(String source, String code, String text, String addedByType, String addedById,
                            String addedByRole, Instant addedAt) {
    }

    public record DecisionActor(String source, String type, String id, String role, Instant decidedAt) {
    }

    private static final String ROW_COLUMNS = """
            id, restaurant_id, order_id, scope, status, requested_by_type, requested_by_id, requested_by_role,
            reported_staff_id, requested_at, kitchen_confirmation_required, fulfillment_status_at_request, payment_status_at_request,
            decision_source, decided_by_type, decided_by_id, decided_by_role, decided_at, rejection_code,
            rejection_note, currency, previous_due_minor, cancelled_gross_minor, cancelled_net_minor,
            cancelled_vat_minor, new_due_minor, collected_minor, refund_required_minor, refunded_minor,
            staff_reason_added_at, staff_reason_added_by, version, created_at""";

    private static final String SELECT_ROW = "SELECT " + ROW_COLUMNS + " FROM order_cancellations";

    private final JdbcTemplate jdbc;

    public CancellationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID restaurantId, UUID orderId, UUID cancellationId) {
        return jdbc.query(SELECT_ROW + " WHERE restaurant_id = ? AND order_id = ?"
                        + " AND id = ?", (rs, i) -> map(rs), restaurantId, orderId, cancellationId).stream().findFirst();
    }

    /** All cancellations of an order, oldest first. */
    public List<Row> findByOrder(UUID restaurantId, UUID orderId) {
        return jdbc.query(SELECT_ROW + " WHERE restaurant_id = ? AND order_id = ?"
                + " ORDER BY requested_at, id", (rs, i) -> map(rs), restaurantId, orderId);
    }

    /** The one request waiting for a decision, if any (the database allows at most one per order). */
    public Optional<Row> findOpen(UUID orderId) {
        return jdbc.query(SELECT_ROW + " WHERE order_id = ? AND status = 'REQUESTED'",
                (rs, i) -> map(rs), orderId).stream().findFirst();
    }

    public List<ItemRow> findItems(UUID cancellationId) {
        return jdbc.query("""
                SELECT order_item_id, product_id, product_name_snapshot, quantity, unit_gross_minor, tax_rate_bps,
                       gross_minor, net_minor, vat_minor
                FROM order_cancellation_items WHERE cancellation_id = ? ORDER BY product_name_snapshot, order_item_id
                """, (rs, i) -> new ItemRow(rs.getObject("order_item_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("product_name_snapshot"), rs.getInt("quantity"),
                rs.getLong("unit_gross_minor"), rs.getInt("tax_rate_bps"), rs.getLong("gross_minor"),
                rs.getLong("net_minor"), rs.getLong("vat_minor")), cancellationId);
    }

    public List<ReasonRow> findReasons(UUID cancellationId) {
        return jdbc.query("""
                SELECT source, reason_code, reason_text, added_by_type, added_by_id, added_by_role, added_at
                FROM order_cancellation_reasons WHERE cancellation_id = ? ORDER BY seq
                """, (rs, i) -> new ReasonRow(rs.getString("source"), rs.getString("reason_code"),
                rs.getString("reason_text"), rs.getString("added_by_type"), rs.getString("added_by_id"),
                rs.getString("added_by_role"), rs.getTimestamp("added_at").toInstant()), cancellationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Row r) {
        jdbc.update("""
                INSERT INTO order_cancellations (
                    id, restaurant_id, order_id, scope, status, requested_by_type, requested_by_id, requested_by_role,
                    reported_staff_id, requested_at, kitchen_confirmation_required, fulfillment_status_at_request,
                    payment_status_at_request,
                    decision_source, decided_by_type, decided_by_id, decided_by_role, decided_at, rejection_code,
                    rejection_note, currency, previous_due_minor, cancelled_gross_minor, cancelled_net_minor,
                    cancelled_vat_minor, new_due_minor, collected_minor, refund_required_minor, refunded_minor,
                    staff_reason_added_at, staff_reason_added_by, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.id(), r.restaurantId(), r.orderId(), r.scope(), r.status(), r.requestedByType(), r.requestedById(),
                r.requestedByRole(), r.reportedStaffId(), timestamp(r.requestedAt()), r.kitchenConfirmationRequired(),
                r.fulfillmentStatusAtRequest(), r.paymentStatusAtRequest(), r.decisionSource(), r.decidedByType(),
                r.decidedById(), r.decidedByRole(), timestamp(r.decidedAt()), r.rejectionCode(), r.rejectionNote(),
                r.currency(), r.previousDueMinor(), r.cancelledGrossMinor(), r.cancelledNetMinor(), r.cancelledVatMinor(), r.newDueMinor(),
                r.collectedMinor(), r.refundRequiredMinor(), r.refundedMinor(), timestamp(r.staffReasonAddedAt()),
                r.staffReasonAddedBy(), r.version(), timestamp(r.createdAt()), timestamp(r.createdAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertItems(UUID restaurantId, UUID cancellationId, List<ItemRow> items) {
        for (ItemRow item : items) {
            jdbc.update("""
                    INSERT INTO order_cancellation_items (id, restaurant_id, cancellation_id, order_item_id, product_id,
                                                          product_name_snapshot, quantity, unit_gross_minor, tax_rate_bps,
                                                          gross_minor, net_minor, vat_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), restaurantId, cancellationId, item.orderItemId(), item.productId(),
                    item.productName(), item.quantity(), item.unitGrossMinor(), item.taxRateBps(), item.grossMinor(),
                    item.netMinor(), item.vatMinor());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertReasons(UUID restaurantId, UUID cancellationId, List<ReasonRow> reasons) {
        for (ReasonRow reason : reasons) {
            jdbc.update("""
                    INSERT INTO order_cancellation_reasons (id, restaurant_id, cancellation_id, source, reason_code,
                                                            reason_text, added_by_type, added_by_id, added_by_role, added_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), restaurantId, cancellationId, reason.source(), reason.code(), reason.text(),
                    reason.addedByType(), reason.addedById(), reason.addedByRole(), timestamp(reason.addedAt()));
        }
    }

    /**
     * The Kitchen confirmed (or the amounts were recalculated at the decision): REQUESTED becomes APPLIED, once. The
     * lines, the scope and who asked never change; the amounts are those of the order as it is now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approve(UUID id, DecisionActor actor, long previousDueMinor, long newDueMinor, long collectedMinor,
                        long refundRequiredMinor) {
        requireOne(jdbc.update("""
                UPDATE order_cancellations SET status = 'APPLIED', decision_source = ?, decided_by_type = ?,
                    decided_by_id = ?, decided_by_role = ?, decided_at = ?, previous_due_minor = ?, new_due_minor = ?,
                    collected_minor = ?, refund_required_minor = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, actor.source(), actor.type(), actor.id(), actor.role(), timestamp(actor.decidedAt()), previousDueMinor,
                newDueMinor, collectedMinor, refundRequiredMinor, timestamp(actor.decidedAt()), id), id);
    }

    /** REQUESTED becomes REJECTED, once. Nothing was cancelled, so the cancelled amounts are zero and the due is as it was. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(UUID id, DecisionActor actor, String rejectionCode, String rejectionNote, long currentDueMinor,
                       long collectedMinor) {
        requireOne(jdbc.update("""
                UPDATE order_cancellations SET status = 'REJECTED', decision_source = ?, decided_by_type = ?,
                    decided_by_id = ?, decided_by_role = ?, decided_at = ?, rejection_code = ?, rejection_note = ?,
                    previous_due_minor = ?, new_due_minor = ?, cancelled_gross_minor = 0, cancelled_net_minor = 0,
                    cancelled_vat_minor = 0, collected_minor = ?, refund_required_minor = 0, version = version + 1,
                    updated_at = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, actor.source(), actor.type(), actor.id(), actor.role(), timestamp(actor.decidedAt()), rejectionCode,
                rejectionNote, currentDueMinor, currentDueMinor, collectedMinor, timestamp(actor.decidedAt()), id), id);
    }

    /** A waiter completes the reason of a cancellation, once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markStaffReason(UUID id, String staffUserId, Instant at) {
        requireOne(jdbc.update("""
                UPDATE order_cancellations SET staff_reason_added_at = ?, staff_reason_added_by = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND staff_reason_added_at IS NULL
                """, timestamp(at), staffUserId, timestamp(at), id), id);
    }

    private static void requireOne(int updated, UUID id) {
        if (updated != 1) {
            throw new IllegalStateException("cancellation " + id + " was not in the expected state");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("scope"), rs.getString("status"),
                rs.getString("requested_by_type"), rs.getString("requested_by_id"), rs.getString("requested_by_role"),
                rs.getObject("reported_staff_id", UUID.class), instant(rs, "requested_at"), rs.getBoolean("kitchen_confirmation_required"),
                rs.getString("fulfillment_status_at_request"), rs.getString("payment_status_at_request"),
                rs.getString("decision_source"), rs.getString("decided_by_type"), rs.getString("decided_by_id"),
                rs.getString("decided_by_role"), instant(rs, "decided_at"), rs.getString("rejection_code"),
                rs.getString("rejection_note"), rs.getString("currency"), rs.getLong("previous_due_minor"),
                rs.getLong("cancelled_gross_minor"), rs.getLong("cancelled_net_minor"), rs.getLong("cancelled_vat_minor"),
                rs.getLong("new_due_minor"), rs.getLong("collected_minor"), rs.getLong("refund_required_minor"),
                rs.getLong("refunded_minor"), instant(rs, "staff_reason_added_at"), rs.getString("staff_reason_added_by"),
                rs.getLong("version"), instant(rs, "created_at"));
    }
}
