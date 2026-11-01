package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.CartItemOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.infrastructure.persistence.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the V41 migration on real PostgreSQL (Flyway + Hibernate {@code ddl-auto=validate} run at context start)
 * and the constraints the cart logic relies on.
 */
@SpringBootTest
class CartPersistenceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    @Autowired
    private CartRepository carts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private UUID restaurant;
    private UUID guest;

    @BeforeEach
    void freshIds() {
        restaurant = UUID.randomUUID();
        guest = UUID.randomUUID();
    }

    private int insertCart() {
        return tx.execute(status -> carts.insertActiveIfAbsent(UUID.randomUUID(), restaurant, UUID.randomUUID(),
                UUID.randomUUID(), guest, NOW));
    }

    @Test
    void secondActiveCartForSameGuestIsIgnoredNotDuplicated() {
        assertEquals(1, insertCart());
        assertEquals(0, insertCart());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM carts WHERE guest_session_id = ?", Integer.class, guest));
    }

    @Test
    void sameGuestIdInAnotherRestaurantIsAnIndependentCart() {
        insertCart();
        restaurant = UUID.randomUUID();
        assertEquals(1, insertCart());
    }

    @Test
    void checkedOutCartDoesNotBlockANewActiveCart() {
        insertCart();
        jdbc.update("UPDATE carts SET status = 'CHECKED_OUT' WHERE guest_session_id = ?", guest);
        assertEquals(1, insertCart());
    }

    @Test
    void cartItemsAndOptionsPersistAndCascadeOnDelete() {
        insertCart();
        UUID itemId = tx.execute(status -> {
            Cart cart = carts.findActiveForUpdate(restaurant, guest).orElseThrow();
            CartItem item = new CartItem(cart, UUID.randomUUID(), 2, "k".repeat(64), "no salt", "Burger", "TRY",
                    25_000L, 1_000, null, NOW);
            item.addOption(new CartItemOption(item, UUID.randomUUID(), SelectionAction.REMOVE, 1, "Onion", 0L));
            item.addOption(new CartItemOption(item, UUID.randomUUID(), SelectionAction.ADD, 2, "Cheese", 2_500L));
            cart.addItem(item);
            cart.touch(NOW);
            return item.getId();
        });

        Cart reloaded = carts.findActiveWithItems(restaurant, guest).orElseThrow();
        assertEquals(1, reloaded.getVersion());
        assertEquals(2, reloaded.findItem(itemId).orElseThrow().getOptions().size());

        tx.executeWithoutResult(status -> carts.findActiveForUpdate(restaurant, guest).orElseThrow().clearItems());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE restaurant_id = ?", Integer.class, restaurant));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_item_options WHERE restaurant_id = ?", Integer.class, restaurant));
    }

    @Test
    void sameProductAndSelectionKeyCannotBeStoredTwiceInOneCart() {
        insertCart();
        UUID productId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> {
            Cart cart = carts.findActiveForUpdate(restaurant, guest).orElseThrow();
            cart.addItem(new CartItem(cart, productId, 1, "a".repeat(64), null, "Burger", "TRY", 100L, null, null, NOW));
            cart.addItem(new CartItem(cart, productId, 1, "a".repeat(64), null, "Burger", "TRY", 100L, null, null, NOW));
        }));
    }

    @Test
    void databaseRejectsInvalidQuantityAndCrossTenantItems() {
        insertCart();
        UUID cartId = jdbc.queryForObject("SELECT id FROM carts WHERE guest_session_id = ?", UUID.class, guest);
        String insert = "INSERT INTO cart_items (id, restaurant_id, cart_id, product_id, quantity, selection_key,"
                + " product_name_snapshot, currency, unit_gross_minor_snapshot, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'p', 'TRY', 1, now(), now())";

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), restaurant,
                cartId, UUID.randomUUID(), 0, "x"));
        // right cart id but another tenant's restaurant id: the composite FK (restaurant_id, cart_id) rejects it
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(),
                UUID.randomUUID(), cartId, UUID.randomUUID(), 1, "x"));
    }
}
