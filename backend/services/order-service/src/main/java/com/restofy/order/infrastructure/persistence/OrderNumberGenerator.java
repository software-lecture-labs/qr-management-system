package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Allocates human-readable order numbers, unique per restaurant ({@code ORD-000001}, {@code ORD-000002}, ...).
 *
 * <p>The number comes from one atomic upsert on a per-restaurant counter row, so concurrent checkouts can never
 * receive the same value, and a rolled-back checkout rolls its number back too (no gaps from failures). The row lock
 * is held until the checkout transaction ends, so allocation is the last step before commit. The number is only a
 * display identifier: it is sequential and guessable, and must never be used to authorize access to an order.
 * Uniqueness is additionally enforced by {@code UNIQUE (restaurant_id, order_no)}.
 */
@Component
public class OrderNumberGenerator {

    static final String PREFIX = "ORD-";

    private final JdbcTemplate jdbc;

    public OrderNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID restaurantId) {
        Long value = jdbc.queryForObject("""
                INSERT INTO order_number_counters (restaurant_id, last_value) VALUES (?, 1)
                ON CONFLICT (restaurant_id) DO UPDATE SET last_value = order_number_counters.last_value + 1
                RETURNING last_value
                """, Long.class, restaurantId);
        return format(value);
    }

    static String format(long value) {
        return PREFIX + String.format("%06d", value);
    }
}
