package com.restofy.order.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyPropertiesTests {

    private static IdempotencyProperties with(Duration cart, Duration checkout, int batch) {
        return new IdempotencyProperties(cart, checkout, Duration.ofHours(72), Duration.ofHours(1),
                Duration.ofMinutes(5), batch, Duration.ofDays(30));
    }

    @Test
    void theInitialPolicyIs24HoursForCartItemsAnd72HoursForCheckoutAndCancellation() {
        IdempotencyProperties defaults = with(Duration.ofHours(24), Duration.ofHours(72), 500);
        assertEquals(Duration.ofHours(24), defaults.cartItemRetention());
        assertEquals(Duration.ofHours(72), defaults.checkoutRetention());
        assertEquals(Duration.ofHours(72), defaults.cancellationRetention());
    }

    @Test
    void theApplicationPropertiesFileCarriesThatPolicy() throws Exception {
        var properties = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        }
        assertEquals("PT24H", properties.getProperty("restofy.idempotency.cart-item-retention"));
        assertEquals("PT72H", properties.getProperty("restofy.idempotency.checkout-retention"));
        assertEquals("PT72H", properties.getProperty("restofy.idempotency.cancellation-retention"));
    }

    @Test
    void rejectsNonPositiveDurationsAndBadBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> with(Duration.ZERO, Duration.ofHours(72), 500));
        assertThrows(IllegalArgumentException.class, () -> with(Duration.ofHours(24), Duration.ofHours(-1), 500));
        assertThrows(IllegalArgumentException.class, () -> with(null, Duration.ofHours(72), 500));
        assertThrows(IllegalArgumentException.class, () -> with(Duration.ofHours(24), Duration.ofHours(72), 0));
        assertThrows(IllegalArgumentException.class, () -> with(Duration.ofHours(24), Duration.ofHours(72), 10_001));
    }
}
