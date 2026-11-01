package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which external events were already processed, so a redelivered event is recognised. A row is written in the
 * same transaction as the change it caused ({@code MANDATORY}); if that transaction rolls back, the event is not
 * remembered and the sender may retry it.
 */
@Repository
public class InboxStore {

    public static final String KITCHEN = "KITCHEN";
    public static final String PAYMENT = "PAYMENT";

    /** Label of the inbox row of a PAID message that was recorded for reconciliation instead of being applied. */
    public static final String RECONCILIATION_EVENT_TYPE = "PaymentPaidNotApplicable";

    /**
     * Marker type of an earlier, provisional mechanism that {@code payment_captures} (V46) replaced. Nothing writes it any
     * more, but rows of this type, if a database ever holds any, are kept (never deleted by the ordinary cleanup) so
     * nothing earlier is lost. The financial records themselves are {@code payment_captures} and
     * {@code payment_reconciliation_cases}, which no cleanup touches.
     */
    public static final String CAPTURE_APPLIED_TYPE = "PaymentCaptureApplied";
    public static final String CAPTURE_RECONCILED_TYPE = "PaymentCaptureReconciled";
    public static final List<String> PERMANENT_EVENT_TYPES = List.of(CAPTURE_APPLIED_TYPE, CAPTURE_RECONCILED_TYPE);

    public record Processed(UUID orderId, String outcome, String eventType) {
    }

    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Processed> find(String source, UUID eventId) {
        return jdbc.query("SELECT order_id, outcome, event_type FROM inbox_events WHERE source = ? AND event_id = ?",
                        (rs, row) -> new Processed(rs.getObject("order_id", UUID.class), rs.getString("outcome"),
                                rs.getString("event_type")),
                        source, eventId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String source, UUID eventId, UUID restaurantId, UUID orderId, String eventType, String outcome,
                       Instant receivedAt) {
        jdbc.update("""
                INSERT INTO inbox_events (id, source, event_id, restaurant_id, order_id, event_type, outcome, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), source, eventId, restaurantId, orderId, eventType, outcome,
                Timestamp.from(receivedAt));
    }
}
