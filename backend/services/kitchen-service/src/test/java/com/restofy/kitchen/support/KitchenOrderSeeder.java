package com.restofy.kitchen.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-only direct seeding for statuses that Phase 1 cannot produce through the API (PREPARING, READY, CANCELLED
 * only appear once transition commands exist). Rows satisfy the same CHECK constraints as production rows.
 */
public final class KitchenOrderSeeder {

	private final JdbcTemplate jdbc;

	public KitchenOrderSeeder(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the Order service id (orderId) of the seeded row. */
	public UUID seed(UUID restaurantId, UUID kitchenId, String status, Instant receivedAt) {
		UUID orderId = UUID.randomUUID();
		Instant startedAt = "PREPARING".equals(status) || "READY".equals(status) ? receivedAt.plusSeconds(60) : null;
		Instant readyAt = "READY".equals(status) ? receivedAt.plusSeconds(120) : null;
		Instant cancelledAt = "CANCELLED".equals(status) ? receivedAt.plusSeconds(30) : null;
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot,
				    order_number_snapshot, status, received_at, started_at, ready_at, cancelled_at,
				    source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', ?, ?, ?, ?, ?, ?, 1, ?)
				""", kitchenId, restaurantId, orderId, UUID.randomUUID(), "S-" + status, status,
				Timestamp.from(receivedAt), ts(startedAt), ts(readyAt), ts(cancelledAt), UUID.randomUUID());
		return orderId;
	}

	public UUID seed(UUID restaurantId, String status, Instant receivedAt) {
		return seed(restaurantId, UUID.randomUUID(), status, receivedAt);
	}

	private static Timestamp ts(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}
}
