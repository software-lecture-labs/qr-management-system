package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.enums.NotificationDeliveryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State of the persistent queue towards Notification. The queue is the only thing that survives a crash, a
 * restart or a Notification outage, so every state change is a short committed statement and no database
 * transaction is ever held open across the HTTP call.
 */
@Repository
public class NotificationDeliveries {

    /** An event leased for one delivery attempt; {@code attemptCount} already includes this attempt. */
    public record DueDelivery(UUID eventId, UUID serviceRequestId, long callVersion, int attemptCount, String payload) {
    }

    /** Synchronization state of the latest event of a call, as shown next to the call itself. */
    public record Sync(UUID serviceRequestId, UUID eventId, long callVersion, NotificationDeliveryStatus status,
                       int attemptCount, String lastErrorCode, Instant lastAttemptAt, Instant deliveredAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public NotificationDeliveries(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * Retires events that a newer state of the same call has made obsolete, then leases the due ones. Events are
     * full state snapshots, so sending only the newest one loses nothing and an old OPEN can never be sent after
     * a newer RESOLVED. Leasing hides a row until {@code now + lease}: if this process dies mid-attempt the row
     * simply becomes due again and is resent with the same event id.
     */
    public List<DueDelivery> leaseDue(Instant now, int limit, Duration lease) {
        return transaction.execute(status -> {
            jdbc.update("""
                            UPDATE notification_deliveries d SET status = ?, updated_at = ?
                            WHERE d.status = ? AND EXISTS (
                                SELECT 1 FROM notification_deliveries n
                                WHERE n.service_request_id = d.service_request_id AND n.call_version > d.call_version
                                  AND n.status IN (?, ?))
                            """,
                    NotificationDeliveryStatus.SUPERSEDED.name(), Timestamp.from(now),
                    NotificationDeliveryStatus.PENDING.name(), NotificationDeliveryStatus.PENDING.name(),
                    NotificationDeliveryStatus.DELIVERED.name());
            List<DueDelivery> due = jdbc.query("""
                            SELECT d.event_id, d.service_request_id, d.call_version, d.attempt_count, e.payload::text AS payload
                            FROM notification_deliveries d JOIN service_call_events e ON e.id = d.event_id
                            WHERE d.status = ? AND d.next_attempt_at <= ?
                            ORDER BY d.created_at, d.call_version
                            LIMIT ? FOR UPDATE OF d SKIP LOCKED
                            """,
                    (rs, row) -> new DueDelivery(rs.getObject("event_id", UUID.class),
                            rs.getObject("service_request_id", UUID.class), rs.getLong("call_version"),
                            rs.getInt("attempt_count") + 1, rs.getString("payload")),
                    NotificationDeliveryStatus.PENDING.name(), Timestamp.from(now), limit);
            for (DueDelivery delivery : due) {
                jdbc.update("""
                                UPDATE notification_deliveries
                                SET attempt_count = ?, last_attempt_at = ?, next_attempt_at = ?, updated_at = ?
                                WHERE event_id = ?
                                """,
                        delivery.attemptCount(), Timestamp.from(now), Timestamp.from(now.plus(lease)),
                        Timestamp.from(now), delivery.eventId());
            }
            return due;
        });
    }

    public void markDelivered(UUID eventId, Instant now) {
        jdbc.update("""
                        UPDATE notification_deliveries
                        SET status = ?, delivered_at = ?, last_error_code = NULL, updated_at = ?
                        WHERE event_id = ? AND status = ?
                        """,
                NotificationDeliveryStatus.DELIVERED.name(), Timestamp.from(now), Timestamp.from(now), eventId,
                NotificationDeliveryStatus.PENDING.name());
    }

    /** Schedules the next attempt, or parks the event as DEAD when {@code nextAttemptAt} is null. */
    public void markFailed(UUID eventId, String errorCode, Instant nextAttemptAt, Instant now) {
        boolean dead = nextAttemptAt == null;
        jdbc.update("""
                        UPDATE notification_deliveries
                        SET status = ?, last_error_code = ?, next_attempt_at = COALESCE(?, next_attempt_at), updated_at = ?
                        WHERE event_id = ? AND status = ?
                        """,
                dead ? NotificationDeliveryStatus.DEAD.name() : NotificationDeliveryStatus.PENDING.name(), errorCode,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), Timestamp.from(now), eventId,
                NotificationDeliveryStatus.PENDING.name());
    }

    /** Latest event per call, tenant-scoped; calls without any event are simply absent. */
    public Map<UUID, Sync> latestFor(UUID restaurantId, Collection<UUID> serviceRequestIds) {
        if (serviceRequestIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(serviceRequestIds.size(), "?"));
        Object[] arguments = new Object[serviceRequestIds.size() + 1];
        arguments[0] = restaurantId;
        int index = 1;
        for (UUID id : serviceRequestIds) arguments[index++] = id;
        Map<UUID, Sync> latest = new HashMap<>();
        jdbc.query("""
                        SELECT DISTINCT ON (service_request_id) service_request_id, event_id, call_version, status,
                               attempt_count, last_error_code, last_attempt_at, delivered_at
                        FROM notification_deliveries
                        WHERE restaurant_id = ? AND service_request_id IN (""" + placeholders + """
                        )
                        ORDER BY service_request_id, call_version DESC
                        """,
                rs -> {
                    UUID call = rs.getObject("service_request_id", UUID.class);
                    Timestamp attempted = rs.getTimestamp("last_attempt_at");
                    Timestamp delivered = rs.getTimestamp("delivered_at");
                    latest.put(call, new Sync(call, rs.getObject("event_id", UUID.class), rs.getLong("call_version"),
                            NotificationDeliveryStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                            rs.getString("last_error_code"), attempted == null ? null : attempted.toInstant(),
                            delivered == null ? null : delivered.toInstant()));
                },
                arguments);
        return latest;
    }
}
