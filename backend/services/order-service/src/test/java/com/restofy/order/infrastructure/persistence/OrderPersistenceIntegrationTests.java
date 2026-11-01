package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.entity.OrderItemOption;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.enums.SelectionAction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V42/V43 on real PostgreSQL: snapshot persistence and the constraints that protect order integrity. */
@SpringBootTest
class OrderPersistenceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @PersistenceContext
    private EntityManager em;

    private Order newOrder(UUID restaurantId, UUID cartId, String orderNo, long net, long vat, long gross) {
        return new Order(UUID.randomUUID(), orderNo, restaurantId, cartId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TRY", net, vat, gross, "no ice", NOW);
    }

    private void persist(Order order) {
        tx.executeWithoutResult(status -> em.persist(order));
    }

    @Test
    void persistsAnOrderWithItsImmutableSnapshots() {
        UUID restaurant = UUID.randomUUID();
        Order order = newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 45_455, 4_545, 50_000);
        OrderItem item = new OrderItem(order, 1, UUID.randomUUID(), "Burger", 2, "TRY", 25_000, 1_000,
                50_000, 45_455, 4_545, null, null, NOW);
        item.addOption(new OrderItemOption(item, UUID.randomUUID(), SelectionAction.REMOVE, 1, "Onion", 0));
        item.addOption(new OrderItemOption(item, UUID.randomUUID(), SelectionAction.ADD, 2, "Cheese", 2_500));
        order.addItem(item);
        persist(order);

        var row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.getId());
        assertEquals("RECEIVED", row.get("fulfillment_status"));
        assertEquals("NOT_REQUESTED", row.get("payment_status"));
        assertEquals(50_000L, row.get("initial_gross_minor"));
        assertEquals(50_000L, row.get("current_due_minor"));
        assertEquals(0L, row.get("version"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, order.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_item_options WHERE restaurant_id = ?", Integer.class, restaurant));
        assertEquals(FulfillmentStatus.RECEIVED, order.getFulfillmentStatus());
        assertEquals(PaymentStatus.NOT_REQUESTED, order.getPaymentStatus());
    }

    @Test
    void orderNumberIsUniquePerRestaurantButReusableAcrossRestaurants() {
        UUID restaurant = UUID.randomUUID();
        persist(newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 10, 0, 10));
        assertThrows(DataIntegrityViolationException.class,
                () -> persist(newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 10, 0, 10)));
        persist(newOrder(UUID.randomUUID(), UUID.randomUUID(), "ORD-000001", 10, 0, 10));
    }

    @Test
    void aCartCanOnlyBeCheckedOutOnce() {
        UUID restaurant = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        persist(newOrder(restaurant, cart, "ORD-000001", 10, 0, 10));
        assertThrows(DataIntegrityViolationException.class,
                () -> persist(newOrder(restaurant, cart, "ORD-000002", 10, 0, 10)));
    }

    @Test
    void databaseRejectsInconsistentAmounts() {
        UUID restaurant = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class,
                () -> persist(newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 40, 5, 50)));
        Order order = newOrder(restaurant, UUID.randomUUID(), "ORD-000002", 90, 10, 100);
        order.addItem(new OrderItem(order, 1, UUID.randomUUID(), "Bad line", 2, "TRY", 50, 1_000, 99, 90, 9, null, null, NOW));
        assertThrows(DataIntegrityViolationException.class, () -> persist(order));
        Order negative = newOrder(restaurant, UUID.randomUUID(), "ORD-000003", -1, 0, -1);
        assertThrows(DataIntegrityViolationException.class, () -> persist(negative));
    }

    @Test
    void orderItemsCannotBelongToAnotherRestaurantsOrder() {
        UUID restaurant = UUID.randomUUID();
        Order order = newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 10, 0, 10);
        persist(order);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO order_items (id, restaurant_id, order_id, line_no, product_id, product_name_snapshot, ordered_quantity,"
                        + " currency, unit_gross_minor, tax_rate_bps, line_gross_minor, line_net_minor, line_vat_minor, created_at)"
                        + " VALUES (?, ?, ?, 1, ?, 'x', 1, 'TRY', 10, 0, 10, 10, 0, now())",
                UUID.randomUUID(), UUID.randomUUID(), order.getId(), UUID.randomUUID()));
    }

    @Test
    void cancelledQuantityCannotExceedOrderedQuantity() {
        UUID restaurant = UUID.randomUUID();
        Order order = newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 10, 0, 10);
        OrderItem item = new OrderItem(order, 1, UUID.randomUUID(), "Tea", 1, "TRY", 10, 0, 10, 10, 0, null, null, NOW);
        order.addItem(item);
        persist(order);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE order_items SET cancelled_quantity = 2 WHERE id = ?", item.getId()));
    }

    @Test
    void outboxEventsRequireNoDuplicateDestinationPerEvent() {
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version,"
                + " event_type, schema_version, payload, occurred_at) VALUES (?, ?, 'ORDER', ?, 0, 'OrderPlaced', 1,"
                + " '{}'::jsonb, now())", event, UUID.randomUUID(), UUID.randomUUID());
        String delivery = "INSERT INTO outbox_deliveries (id, event_id, destination, status) VALUES (?, ?, 'KITCHEN', 'PENDING')";
        jdbc.update(delivery, UUID.randomUUID(), event);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(delivery, UUID.randomUUID(), event));
    }

    @Test
    void idempotencyKeyIsUniquePerActorAndOperation() {
        UUID restaurant = UUID.randomUUID();
        String insert = "INSERT INTO idempotency_records (id, restaurant_id, actor_scope, operation, idempotency_key,"
                + " request_digest, resource_id, response_status, created_at, expires_at)"
                + " VALUES (?, ?, ?, 'CREATE_ORDER', 'key-1', 'd', ?, 201, now(), now() + interval '1 day')";
        jdbc.update(insert, UUID.randomUUID(), restaurant, "guest:a", UUID.randomUUID());
        jdbc.update(insert, UUID.randomUUID(), restaurant, "guest:b", UUID.randomUUID());
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, UUID.randomUUID(), restaurant, "guest:a", UUID.randomUUID()));
    }

    // ---- the order snapshot is immutable at the database level (V42 triggers) -----------------------------------------

    private Order persistedOrderWithOneItem() {
        UUID restaurant = UUID.randomUUID();
        Order order = newOrder(restaurant, UUID.randomUUID(), "ORD-000001", 45_455, 4_545, 50_000);
        OrderItem item = new OrderItem(order, 1, UUID.randomUUID(), "Burger", 2, "TRY", 25_000, 1_000,
                50_000, 45_455, 4_545, "v1", null, NOW);
        item.addOption(new OrderItemOption(item, UUID.randomUUID(), SelectionAction.ADD, 1, "Cheese", 2_500));
        order.addItem(item);
        persist(order);
        return order;
    }

    @Test
    void theOrderSnapshotCannotBeRewrittenButStatusAndDueCanProgress() {
        Order order = persistedOrderWithOneItem();
        for (String column : new String[]{"initial_gross_minor = 1, initial_net_minor = 1, initial_vat_minor = 0",
                "initial_net_minor = 45454, initial_vat_minor = 4546", "order_no = 'ORD-000099'",
                "currency = 'USD'", "table_session_id = gen_random_uuid()", "guest_session_id = gen_random_uuid()",
                "cart_id = gen_random_uuid()", "placed_at = now()", "note = 'rewritten'"}) {
            DataAccessException error = assertThrows(DataAccessException.class,
                    () -> jdbc.update("UPDATE orders SET " + column + " WHERE id = ?", order.getId()), column);
            assertEquals(true, error.getMessage().contains("immutable"), column + ": " + error.getMessage());
        }
        // what is allowed to move
        assertEquals(1, jdbc.update("UPDATE orders SET fulfillment_status = 'PREPARING', payment_status = 'PENDING',"
                + " current_due_minor = 20000, updated_at = now() WHERE id = ?", order.getId()));
        assertEquals(50_000L, jdbc.queryForObject("SELECT initial_gross_minor FROM orders WHERE id = ?", Long.class, order.getId()));
        assertEquals(20_000L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, order.getId()));
    }

    @Test
    void theOrderLinesAreAFrozenSnapshotExceptForCancelledQuantity() {
        Order order = persistedOrderWithOneItem();
        for (String column : new String[]{"unit_gross_minor = 1", "tax_rate_bps = 0", "ordered_quantity = 3",
                "line_gross_minor = 1, line_net_minor = 1, line_vat_minor = 0", "product_name_snapshot = 'Renamed'",
                "price_version = 'v2'", "line_no = 9", "note = 'rewritten'", "currency = 'USD'"}) {
            DataAccessException error = assertThrows(DataAccessException.class,
                    () -> jdbc.update("UPDATE order_items SET " + column + " WHERE order_id = ?", order.getId()), column);
            assertEquals(true, error.getMessage().contains("immutable"), column + ": " + error.getMessage());
        }
        // the counter moves only together with an applied cancellation (V47); a hand-made change is refused at commit
        UUID orderId = order.getId();
        DataAccessException manual = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE order_items SET cancelled_quantity = 1 WHERE order_id = ?", orderId));
        assertEquals(true, manual.getMessage().contains("does not match"), manual.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?", Integer.class, order.getId()));
    }

    @Test
    void theChosenIngredientsAreFrozenToo() {
        Order order = persistedOrderWithOneItem();
        for (String column : new String[]{"unit_price_delta_minor = 1", "name_snapshot = 'Free cheese'", "quantity = 5"}) {
            DataAccessException error = assertThrows(DataAccessException.class,
                    () -> jdbc.update("UPDATE order_item_options SET " + column + " WHERE restaurant_id = ?",
                            order.getRestaurantId()), column);
            assertEquals(true, error.getMessage().contains("immutable"), column + ": " + error.getMessage());
        }
        assertEquals(2_500L, jdbc.queryForObject("SELECT unit_price_delta_minor FROM order_item_options WHERE restaurant_id = ?",
                Long.class, order.getRestaurantId()));
    }
}
