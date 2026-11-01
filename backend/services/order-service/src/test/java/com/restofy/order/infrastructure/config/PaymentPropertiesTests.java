package com.restofy.order.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentPropertiesTests {

    @Test
    void theInitialToleranceIsFiveMinutesAndConfigurable() throws Exception {
        var properties = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        }
        assertEquals("PT5M", properties.getProperty("restofy.payment.max-clock-skew"));
        assertEquals(Duration.ofMinutes(5), new PaymentProperties(Duration.ofMinutes(5)).maxClockSkew());
        assertEquals(Duration.ofMinutes(30), new PaymentProperties(Duration.ofMinutes(30)).maxClockSkew());
    }

    @Test
    void rejectsZeroNegativeMissingAndAbsurdTolerances() {
        Duration negative = Duration.ofSeconds(-1);
        Duration excessive = Duration.ofHours(2);
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(negative));
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(null));
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(excessive));
    }
}
