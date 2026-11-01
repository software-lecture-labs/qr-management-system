package com.restofy.order.domain.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An order, an order line or a cart line cannot be created with a currency the service cannot charge in. */
class MoneyEntitiesCurrencyTests {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private static Order order(String currency) {
        return new Order(UUID.randomUUID(), "ORD-000001", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), currency, 909, 91, 1_000, null, NOW);
    }

    private static OrderItem orderLine(Order order, String currency) {
        return new OrderItem(order, 1, UUID.randomUUID(), "Burger", 1, currency, 1_000, 1_000, 1_000, 909, 91, null,
                null, NOW);
    }

    private static CartItem cartLine(String currency) {
        Cart cart = new Cart();
        return new CartItem(cart, UUID.randomUUID(), 1, "k", null, "Burger", currency, 1_000, 1_000, null, NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRY", "USD", "EUR", "JPY"})
    void everySupportedCurrencyIsAccepted(String currency) {
        Order order = order(currency);

        assertEquals(currency, order.getCurrency());
        assertEquals(currency, orderLine(order, currency).getCurrency());
        assertEquals(currency, cartLine(currency).getCurrency());
    }

    @Test
    void theCodeIsStoredInItsCanonicalForm() {
        Order order = order(" try ");

        assertEquals("TRY", order.getCurrency());
        assertEquals("TRY", orderLine(order, "try").getCurrency());
        assertEquals("TRY", cartLine("Try").getCurrency());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "TR", "TRYY", "ZZZ", "XXX", "XTS"})
    void anUnsupportedCurrencyCannotCreateAnyOfThem(String currency) {
        Order valid = order("TRY");
        assertThrows(IllegalArgumentException.class, () -> order(currency));
        assertThrows(IllegalArgumentException.class, () -> orderLine(valid, currency));
        assertThrows(IllegalArgumentException.class, () -> cartLine(currency));
    }
}
