package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.enums.ActorType;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.NotificationDeliveryStatus;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only audit events, the delivery rows that queue them for Notification, and idempotency records.
 * Every method is meant to run inside the caller's transaction: the event and its delivery row commit or roll
 * back together with the call change they describe, which is what makes the later HTTP delivery recoverable.
 */
@Repository
public class CallLedger {

    /** The first outcome of an idempotency key; a repeat with the same digest returns it again. */
    public record IdempotencyRecord(UUID serviceRequestId, String requestDigest, int responseStatus) {
    }

    private final JdbcTemplate jdbc;

    public CallLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> findIdempotency(UUID restaurantId, String actorScope, String operation, String key) {
        List<IdempotencyRecord> found = jdbc.query("""
                        SELECT service_request_id, request_digest, response_status FROM idempotency_records
                        WHERE restaurant_id = ? AND actor_scope = ? AND operation = ? AND idempotency_key = ?
                        """,
                (rs, row) -> new IdempotencyRecord(rs.getObject("service_request_id", UUID.class),
                        rs.getString("request_digest").trim(), rs.getInt("response_status")),
                restaurantId, actorScope, operation, key);
        return found.stream().findFirst();
    }

    public void recordIdempotency(UUID restaurantId, String actorScope, String operation, String key,
                                  String requestDigest, UUID serviceRequestId, int responseStatus, Instant now) {
        jdbc.update("""
                        INSERT INTO idempotency_records (id, restaurant_id, actor_scope, operation, idempotency_key,
                                                         request_digest, service_request_id, response_status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), restaurantId, actorScope, operation, key, requestDigest, serviceRequestId,
                responseStatus, Timestamp.from(now));
    }

    /** Writes the audit event and queues its delivery to Notification (due immediately). */
    public void appendEvent(UUID eventId, UUID restaurantId, UUID serviceRequestId, long callVersion,
                            CallEventType type, ServiceRequestStatus from, ServiceRequestStatus to,
                            ActorType actorType, UUID actorId, Instant occurredAt, String payloadJson) {
        jdbc.update("""
                        INSERT INTO service_call_events (id, restaurant_id, service_request_id, call_version, event_type,
                                                         from_status, to_status, actor_type, actor_id, occurred_at, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                eventId, restaurantId, serviceRequestId, callVersion, type.name(), from == null ? null : from.name(),
                to.name(), actorType.name(), actorId, Timestamp.from(occurredAt), payloadJson);
        jdbc.update("""
                        INSERT INTO notification_deliveries (event_id, restaurant_id, service_request_id, call_version, status,
                                                             attempt_count, next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                        """,
                eventId, restaurantId, serviceRequestId, callVersion, NotificationDeliveryStatus.PENDING.name(),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt), Timestamp.from(occurredAt));
    }
}
