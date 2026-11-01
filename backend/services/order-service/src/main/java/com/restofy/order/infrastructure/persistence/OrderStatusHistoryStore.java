package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.history.ChangeActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only status history. Writes must happen in the same transaction as the status change they describe
 * ({@code MANDATORY}), so a change and its history either both exist or neither does. The table itself rejects
 * UPDATE and DELETE.
 */
@Repository
public class OrderStatusHistoryStore {

    public enum Axis { FULFILLMENT, PAYMENT }

    public record Entry(Axis axis, String fromStatus, String toStatus, ChangeActor.Type actorType, String actorId,
                        String actorRole, String source, String reason, Instant occurredAt) {
    }

    public record Change(UUID restaurantId, UUID orderId, Axis axis, String fromStatus, String toStatus,
                         ChangeActor actor, String source, UUID sourceEventId, String reason, Instant occurredAt,
                         String correlationId) {
    }

    private final JdbcTemplate jdbc;

    public OrderStatusHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Change change) {
        jdbc.update("""
                INSERT INTO order_status_history (id, restaurant_id, order_id, state_axis, from_status, to_status,
                                                  actor_type, actor_id, actor_role, source, source_event_id, reason,
                                                  occurred_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), change.restaurantId(), change.orderId(), change.axis().name(),
                change.fromStatus(), change.toStatus(), change.actor().type().name(), change.actor().id(),
                change.actor().role(), change.source(), change.sourceEventId(), change.reason(),
                Timestamp.from(change.occurredAt()), change.correlationId());
    }

    /** The order's timeline, oldest first; the insertion sequence breaks ties so the order is deterministic. */
    public List<Entry> find(UUID restaurantId, UUID orderId) {
        return jdbc.query("""
                SELECT state_axis, from_status, to_status, actor_type, actor_id, actor_role, source, reason, occurred_at
                FROM order_status_history
                WHERE restaurant_id = ? AND order_id = ?
                ORDER BY occurred_at, seq
                """, (rs, row) -> new Entry(Axis.valueOf(rs.getString("state_axis")), rs.getString("from_status"),
                rs.getString("to_status"), ChangeActor.Type.valueOf(rs.getString("actor_type")),
                rs.getString("actor_id"), rs.getString("actor_role"), rs.getString("source"), rs.getString("reason"),
                rs.getTimestamp("occurred_at").toInstant()), restaurantId, orderId);
    }
}
