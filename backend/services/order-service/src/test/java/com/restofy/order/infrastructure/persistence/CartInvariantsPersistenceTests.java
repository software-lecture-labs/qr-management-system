package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.enums.CartStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.persistence.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cart entity's own invariants against real PostgreSQL and Hibernate: a stored currency is validated when the row is
 * loaded and when it is written, and a cart that is no longer ACTIVE stays exactly as it was.
 */
@SpringBootTest
class CartInvariantsPersistenceTests {

    private static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");

    @Autowired
    private CartRepository carts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private UUID restaurant;
    private UUID guest;
    private UUID cartId;

    @BeforeEach
    void openACart() {
        restaurant = UUID.randomUUID();
        guest = UUID.randomUUID();
        cartId = UUID.randomUUID();
        tx.executeWithoutResult(status -> carts.insertActiveIfAbsent(cartId, restaurant, UUID.randomUUID(),
                UUID.randomUUID(), guest, NOW));
    }

    private String storedCurrency() {
        return jdbc.queryForObject("SELECT currency FROM carts WHERE id = ?", String.class, cartId);
    }

    private long storedVersion() {
        return jdbc.queryForObject("SELECT version FROM carts WHERE id = ?", Long.class, cartId);
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type, String messagePart) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause) && String.valueOf(cause.getMessage()).contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    private void addOneLine() {
        tx.executeWithoutResult(status -> {
            Cart cart = carts.findActiveForUpdate(restaurant, guest).orElseThrow();
            cart.useCurrency("TRY");
            cart.addItem(new CartItem(cart, UUID.randomUUID(), 1, "k".repeat(64), null, "Burger", "TRY", 1_000L,
                    1_000, null, NOW));
            cart.touch(NOW);
        });
    }

    // ---- currency -------------------------------------------------------------------------------------------------

    @Test
    void aValidCurrencyIsStoredNormalizedAndHydratesIntoTheEntity() {
        tx.executeWithoutResult(status ->
                carts.findActiveForUpdate(restaurant, guest).orElseThrow().useCurrency("usd"));

        assertEquals("USD", storedCurrency());
        assertEquals("USD", carts.findActiveWithItems(restaurant, guest).orElseThrow().getCurrency());
    }

    @Test
    void aNewCartHydratesWithoutACurrency() {
        assertNull(carts.findActiveWithItems(restaurant, guest).orElseThrow().getCurrency());
    }

    @Test
    void aStoredCurrencyThatIsNotAnIsoCodeIsRefusedWhenTheRowIsLoadedNeverAcceptedSilently() {
        for (String corrupt : new String[]{"ZZZ", "XXX", "AAA"}) {
            // the database only checks the three-letter shape, so these rows exist
            jdbc.update("UPDATE carts SET currency = ? WHERE id = ?", corrupt, cartId);

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> carts.findActiveWithItems(restaurant, guest), corrupt);

            assertTrue(causedBy(failure, IllegalStateException.class, "unsupported currency"), corrupt + ": " + failure);
        }
    }

    @Test
    void anInvalidCurrencyCannotBeWrittenEvenIfSomethingBypassesTheDomainMethod() {
        addOneLine();
        long versionBefore = storedVersion();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            Cart cart = carts.findActiveForUpdate(restaurant, guest).orElseThrow();
            ReflectionTestUtils.setField(cart, "currency", "ZZZ");
            cart.touch(NOW.plusSeconds(1));
        }));

        assertTrue(causedBy(failure, IllegalStateException.class, "unsupported currency"), failure.toString());
        assertEquals("TRY", storedCurrency());
        assertEquals(versionBefore, storedVersion());
    }

    // ---- status transitions ---------------------------------------------------------------------------------------

    @Test
    void aCheckedOutCartIsFinalAndAFailedAttemptToChangeItLeavesTheRowUntouched() {
        addOneLine();
        tx.executeWithoutResult(status -> carts.findActiveForUpdate(restaurant, guest).orElseThrow().checkOut(NOW));
        long version = storedVersion();
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts WHERE id = ?", String.class, cartId));
        assertTrue(tx.execute(status -> carts.findActiveForUpdate(restaurant, guest)).isEmpty(),
                "a checked-out cart is never found as the active one");

        BusinessException secondCheckout = assertThrows(BusinessException.class, () -> tx.executeWithoutResult(
                status -> carts.findById(cartId).orElseThrow().checkOut(NOW.plusSeconds(1))));
        BusinessException newLine = assertThrows(BusinessException.class, () -> tx.executeWithoutResult(status -> {
            Cart cart = carts.findById(cartId).orElseThrow();
            cart.addItem(new CartItem(cart, UUID.randomUUID(), 1, "z".repeat(64), null, "Salad", "TRY", 500L, null,
                    null, NOW));
        }));
        BusinessException currency = assertThrows(BusinessException.class, () -> tx.executeWithoutResult(
                status -> carts.findById(cartId).orElseThrow().useCurrency("EUR")));

        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, secondCheckout.code());
        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, newLine.code());
        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, currency.code());
        assertEquals(version, storedVersion());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id = ?", Integer.class, cartId));
        assertEquals("TRY", storedCurrency());
        assertEquals(CartStatus.CHECKED_OUT, carts.findById(cartId).orElseThrow().getStatus());
    }

    @Test
    void anEmptyCartCannotBeCheckedOutAndStaysActive() {
        BusinessException failure = assertThrows(BusinessException.class, () -> tx.executeWithoutResult(
                status -> carts.findActiveForUpdate(restaurant, guest).orElseThrow().checkOut(NOW)));

        assertEquals(ErrorCode.CART_EMPTY, failure.code());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts WHERE id = ?", String.class, cartId));
    }
}
