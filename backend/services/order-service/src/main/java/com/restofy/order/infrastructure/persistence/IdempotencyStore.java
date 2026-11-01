package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which command (actor + operation + key) already produced which resource. The record is inserted inside
 * the same transaction as the business change, so it exists if and only if the change was committed. It stores only
 * a digest of the request and the id of the resulting resource, never the request or response body.
 */
@Repository
public class IdempotencyStore {

    public record Record(String requestDigest, UUID resourceId, int responseStatus) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> find(UUID restaurantId, String actorScope, String operation, String key) {
        return jdbc.query("""
                        SELECT request_digest, resource_id, response_status FROM idempotency_records
                        WHERE restaurant_id = ? AND actor_scope = ? AND operation = ? AND idempotency_key = ?
                        """,
                (rs, row) -> new Record(rs.getString("request_digest"), rs.getObject("resource_id", UUID.class),
                        rs.getInt("response_status")),
                restaurantId, actorScope, operation, key).stream().findFirst();
    }

    /**
     * Claims the key for this transaction. A concurrent claim of the same key blocks until the other transaction
     * finishes: it then returns {@code false} if that one committed, or succeeds if it rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaim(UUID restaurantId, String actorScope, String operation, String key, String requestDigest,
                            UUID resourceId, int responseStatus, Instant now, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO idempotency_records (id, restaurant_id, actor_scope, operation, idempotency_key,
                                                 request_digest, resource_id, response_status, status, created_at,
                                                 expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                ON CONFLICT (restaurant_id, actor_scope, operation, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), restaurantId, actorScope, operation, key, requestDigest, resourceId,
                responseStatus, Timestamp.from(now), Timestamp.from(expiresAt)) == 1;
    }

    /**
     * Deletes up to {@code batchSize} COMPLETED records whose retention has passed. Records that are still
     * {@code IN_PROGRESS} are never touched, whatever their age. Safe to run repeatedly and concurrently: each batch
     * only deletes rows it can lock and skips the ones another run is already deleting.
     *
     * @return the number of deleted records
     */
    @Transactional
    public int purgeExpired(Instant now, int batchSize) {
        return jdbc.update("""
                DELETE FROM idempotency_records WHERE id IN (
                    SELECT id FROM idempotency_records
                    WHERE status = 'COMPLETED' AND expires_at < ?
                    ORDER BY expires_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now), batchSize);
    }
}
