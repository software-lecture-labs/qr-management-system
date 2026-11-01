package com.restofy.qr.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class IdleWindow {
    private static final Duration TIMEOUT = Duration.ofMinutes(15);

    private IdleWindow() {
    }

    public static Instant storedTime(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant deadline(Instant activity) {
        return storedTime(activity).plus(TIMEOUT);
    }
}
