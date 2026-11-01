package com.restofy.order.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessagingRetentionPropertiesTests {

    @Test
    void theApplicationPropertiesFileCarriesTheInitialPolicy() throws Exception {
        var properties = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        }
        assertEquals("P90D", properties.getProperty("restofy.messaging.inbox-retention"));
        assertEquals("P30D", properties.getProperty("restofy.messaging.delivered-outbox-retention"));
    }

    @Test
    void theDeduplicationWindowCannotBeConfiguredShorterThanSevenDays() {
        Duration sixDays = Duration.ofDays(6);
        Duration oneHour = Duration.ofHours(1);
        Duration thirtyDays = Duration.ofDays(30);
        assertEquals(Duration.ofDays(7), new MessagingRetentionProperties(Duration.ofDays(7), Duration.ofDays(30))
                .inboxRetention());
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingRetentionProperties(sixDays, thirtyDays));
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingRetentionProperties(oneHour, thirtyDays));
        assertThrows(IllegalArgumentException.class, () -> new MessagingRetentionProperties(null, thirtyDays));
    }

    @Test
    void theOutboxRetentionNeedsAtLeastADay() {
        Duration ninetyDays = Duration.ofDays(90);
        Duration twentyThreeHours = Duration.ofHours(23);
        assertEquals(Duration.ofDays(1), new MessagingRetentionProperties(Duration.ofDays(90), Duration.ofDays(1))
                .deliveredOutboxRetention());
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingRetentionProperties(ninetyDays, twentyThreeHours));
        assertThrows(IllegalArgumentException.class, () -> new MessagingRetentionProperties(ninetyDays, null));
    }
}
