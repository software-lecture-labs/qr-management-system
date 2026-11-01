package com.restofy.order.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancellationPropertiesTests {

    @Test
    void theInitialThresholdIsFifteenMinutesAndConfigurableAsAPlainProperty() throws Exception {
        var properties = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        }
        assertEquals("PT15M", properties.getProperty("restofy.cancellation.pending-attention-after"));
        assertEquals(Duration.ofMinutes(15), new CancellationProperties(Duration.ofMinutes(15)).pendingAttentionAfter());
        assertEquals(Duration.ofHours(2), new CancellationProperties(Duration.ofHours(2)).pendingAttentionAfter());
    }

    @Test
    void rejectsZeroNegativeMissingAndAbsurdThresholds() {
        Duration negative = Duration.ofSeconds(-1);
        Duration excessive = Duration.ofDays(31);
        assertThrows(IllegalArgumentException.class, () -> new CancellationProperties(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CancellationProperties(negative));
        assertThrows(IllegalArgumentException.class, () -> new CancellationProperties(null));
        assertThrows(IllegalArgumentException.class, () -> new CancellationProperties(excessive));
    }
}
