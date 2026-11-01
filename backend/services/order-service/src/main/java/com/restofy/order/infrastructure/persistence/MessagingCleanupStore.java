package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Housekeeping for the inbox and the outbox, one bounded batch at a time. Orders, order items, the status history and
 * idempotency records are never touched: the audit trail of an order does not depend on its messages. Rows are taken with
 * {@code FOR UPDATE SKIP LOCKED}, so a concurrent business transaction or another instance is never blocked or disturbed.
 */
@Repository
public class MessagingCleanupStore {

    private final JdbcTemplate jdbc;

    public MessagingCleanupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes up to {@code batchSize} processed inbox events received before {@code olderThan}. Every inbox row is
     * already terminal (it is written in the same transaction as the change it caused, so it only exists once the event
     * was processed or deliberately ignored). Financial records ({@link InboxStore#PERMANENT_EVENT_TYPES}: reconciliation
     * cases and the capture markers) are never deleted: they are what keeps a redelivered payment harmless for good.
     *
     * @return the number of deleted rows
     */
    @Transactional
    public int purgeInbox(Instant olderThan, int batchSize) {
        return jdbc.update("""
                DELETE FROM inbox_events WHERE id IN (
                    SELECT id FROM inbox_events WHERE received_at < ? AND event_type <> ALL (?)
                    ORDER BY received_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """, ps -> {
                    ps.setTimestamp(1, Timestamp.from(olderThan));
                    ps.setArray(2, ps.getConnection().createArrayOf("varchar",
                            InboxStore.PERMANENT_EVENT_TYPES.toArray()));
                    ps.setInt(3, batchSize);
                });
    }

    /**
     * Deletes up to {@code batchSize} outbox events (with their deliveries) for which EVERY delivery is DELIVERED and the
     * last of them happened before {@code olderThan}. An event with any pending or failed delivery, with a delivery that
     * has no delivery time, or with no delivery at all is never deleted, and neither is an
     * {@code OrderPaymentReconciliationRequired} event whatever its delivery state: it is the financial record of a
     * payment that could not be applied. The event row is locked first, so nothing
     * changes underneath the delete.
     *
     * @return the number of deleted outbox events
     */
    @Transactional
    public int purgeDeliveredOutbox(Instant olderThan, int batchSize) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT e.id FROM outbox_events e
                WHERE EXISTS (SELECT 1 FROM outbox_deliveries d WHERE d.event_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM outbox_deliveries d WHERE d.event_id = e.id
                                  AND (d.status <> 'DELIVERED' OR d.delivered_at IS NULL OR d.delivered_at >= ?))
                  AND e.event_type <> 'OrderPaymentReconciliationRequired'
                ORDER BY e.occurred_at
                LIMIT ?
                FOR UPDATE OF e SKIP LOCKED
                """, UUID.class, Timestamp.from(olderThan), batchSize);
        if (ids.isEmpty()) {
            return 0;
        }
        jdbc.update("DELETE FROM outbox_deliveries WHERE event_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())));
        return jdbc.update("DELETE FROM outbox_events WHERE id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())));
    }
}
