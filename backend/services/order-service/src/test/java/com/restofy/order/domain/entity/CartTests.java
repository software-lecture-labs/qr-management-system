package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.CartStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The invariants the Cart entity guards itself: status transitions, immutability once final, and the currency. */
class CartTests {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private static Cart cart(CartStatus status, String currency) {
        Cart cart = new Cart();
        ReflectionTestUtils.setField(cart, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(cart, "restaurantId", UUID.randomUUID());
        ReflectionTestUtils.setField(cart, "status", status);
        ReflectionTestUtils.setField(cart, "currency", currency);
        ReflectionTestUtils.setField(cart, "updatedAt", NOW);
        return cart;
    }

    private static CartItem line(Cart cart) {
        return new CartItem(cart, UUID.randomUUID(), 1, "key-" + UUID.randomUUID(), null, "Burger", "TRY", 1_000, 1_000,
                null, NOW);
    }

    private static Cart activeCartWithOneLine() {
        Cart cart = cart(CartStatus.ACTIVE, null);
        cart.useCurrency("TRY");
        cart.addItem(line(cart));
        return cart;
    }

    private static ErrorCode failure(Runnable action) {
        return assertThrows(BusinessException.class, action::run).code();
    }

    // ---- status transitions -----------------------------------------------------------------------------------

    @Test
    void anActiveCartWithItemsChecksOutOnceAndAdvancesItsTimestamp() {
        Cart cart = activeCartWithOneLine();
        Instant before = cart.getUpdatedAt();

        cart.checkOut(NOW.plusSeconds(5));

        assertEquals(CartStatus.CHECKED_OUT, cart.getStatus());
        assertTrue(cart.getUpdatedAt().isAfter(before));
    }

    @Test
    void checkingOutTwiceIsRefusedAndChangesNothing() {
        Cart cart = activeCartWithOneLine();
        cart.checkOut(NOW);
        Instant stamp = cart.getUpdatedAt();

        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, failure(() -> cart.checkOut(NOW.plusSeconds(60))));

        assertEquals(CartStatus.CHECKED_OUT, cart.getStatus());
        assertEquals(stamp, cart.getUpdatedAt());
    }

    @Test
    void anEmptyCartOrOneWithoutACurrencyCannotBeOrdered() {
        Cart empty = cart(CartStatus.ACTIVE, null);
        assertEquals(ErrorCode.CART_EMPTY, failure(() -> empty.checkOut(NOW)));
        assertEquals(CartStatus.ACTIVE, empty.getStatus());

        Cart emptiedAfterAdding = activeCartWithOneLine();
        emptiedAfterAdding.clearItems();
        assertEquals(ErrorCode.CART_EMPTY, failure(() -> emptiedAfterAdding.checkOut(NOW)));
        assertEquals(CartStatus.ACTIVE, emptiedAfterAdding.getStatus());
    }

    @Test
    void anExpiredCartCannotBeOrdered() {
        Cart expired = cart(CartStatus.EXPIRED, "TRY");
        assertEquals(ErrorCode.CONFLICT, failure(() -> expired.checkOut(NOW)));
        assertEquals(CartStatus.EXPIRED, expired.getStatus());
    }

    @Test
    void aCheckedOutCartCannotBeChangedInAnyWay() {
        Cart cart = activeCartWithOneLine();
        CartItem only = cart.getItems().iterator().next();
        cart.checkOut(NOW);

        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, failure(() -> cart.addItem(line(cart))));
        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, failure(() -> cart.removeItem(only)));
        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, failure(cart::clearItems));
        assertEquals(ErrorCode.CART_ALREADY_CHECKED_OUT, failure(() -> cart.useCurrency("TRY")));

        assertEquals(1, cart.getItems().size());
        assertSame(only, cart.getItems().iterator().next());
        assertEquals("TRY", cart.getCurrency());
    }

    @Test
    void anExpiredCartCannotBeChangedEither() {
        Cart cart = cart(CartStatus.EXPIRED, "TRY");

        assertEquals(ErrorCode.CONFLICT, failure(() -> cart.addItem(line(cart))));
        assertEquals(ErrorCode.CONFLICT, failure(cart::clearItems));
        assertEquals(ErrorCode.CONFLICT, failure(() -> cart.useCurrency("EUR")));
        assertEquals("TRY", cart.getCurrency());
    }

    @Test
    void itemsAreExposedReadOnly() {
        Cart cart = activeCartWithOneLine();

        Set<CartItem> exposed = cart.getItems();
        CartItem another = line(cart);
        assertThrows(UnsupportedOperationException.class, exposed::clear);
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(another));
        assertEquals(1, cart.getItems().size());
    }

    // ---- currency invariant ---------------------------------------------------------------------------------------

    @Test
    void aSupportedCurrencyIsAcceptedAndNormalized() {
        Cart cart = cart(CartStatus.ACTIVE, null);

        cart.useCurrency("usd");
        assertEquals("USD", cart.getCurrency());
        cart.useCurrency("  eur ");
        assertEquals("EUR", cart.getCurrency());
        cart.useCurrency("TRY");
        assertEquals("TRY", cart.getCurrency());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "TR", "TRYY", "TR1", "ZZZ", "XXX", "XTS", "XAU", "T RY", "ТRY"})
    void anUnsupportedCurrencyIsRefusedAndTheCartKeepsItsCurrency(String invalid) {
        Cart cart = cart(CartStatus.ACTIVE, "TRY");

        assertThrows(IllegalArgumentException.class, () -> cart.useCurrency(invalid));

        assertEquals("TRY", cart.getCurrency());
    }

    @Test
    void nullIsRefusedNotTurnedIntoADefaultCurrency() {
        Cart cart = cart(CartStatus.ACTIVE, null);

        assertThrows(IllegalArgumentException.class, () -> cart.useCurrency(null));

        assertNull(cart.getCurrency());
    }

    @Test
    void theCurrencyOfACartWithItemsCannotChangeButCanBeRepeated() {
        Cart cart = activeCartWithOneLine();

        cart.useCurrency("try");
        assertEquals("TRY", cart.getCurrency());
        assertEquals(ErrorCode.CURRENCY_MISMATCH, failure(() -> cart.useCurrency("EUR")));
        assertEquals("TRY", cart.getCurrency());
    }

    @Test
    void anEmptiedCartMayChooseANewCurrency() {
        Cart cart = activeCartWithOneLine();
        cart.clearItems();

        cart.useCurrency("EUR");

        assertEquals("EUR", cart.getCurrency());
    }

    @Test
    void aStoredCurrencyIsVerifiedWhenARowIsLoadedOrWritten() {
        cart(CartStatus.ACTIVE, null).requireValidStoredCurrency();
        cart(CartStatus.ACTIVE, "TRY").requireValidStoredCurrency();
        cart(CartStatus.ACTIVE, "JPY").requireValidStoredCurrency();

        // stored data is never repaired: not even a harmless-looking lower-case code is quietly accepted
        for (String corrupt : new String[]{"ZZZ", "XXX", "try", " TRY", "TR", "AAA"}) {
            Cart cart = cart(CartStatus.ACTIVE, corrupt);
            assertThrows(IllegalStateException.class, cart::requireValidStoredCurrency, corrupt);
        }
    }
}
