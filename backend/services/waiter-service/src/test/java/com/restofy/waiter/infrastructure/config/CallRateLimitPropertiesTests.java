package com.restofy.waiter.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallRateLimitPropertiesTests {

    @Test
    void theWindowIsTheConfiguredNumberOfSeconds() {
        assertEquals(Duration.ofSeconds(600), new CallRateLimitProperties(6, 600).window());
    }

    @Test
    void aLimitWithoutRoomForOneCallOrWithoutAWindowIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new CallRateLimitProperties(0, 600));
        assertThrows(IllegalArgumentException.class, () -> new CallRateLimitProperties(6, 0));
    }
}
