package com.restofy.order.infrastructure.config;

import com.restofy.order.application.service.DataRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic housekeeping. The first run waits {@code restofy.idempotency.cleanup-initial-delay} (5 minutes by default)
 * so application start and tests are never affected; a failing run is logged and retried at the next interval.
 */
@Slf4j
@Component
public class RetentionJob {

    private final DataRetentionService retention;

    public RetentionJob(DataRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${restofy.idempotency.cleanup-initial-delay:PT5M}",
            fixedDelayString = "${restofy.idempotency.cleanup-interval:PT1H}")
    public void run() {
        try {
            retention.runAll();
        } catch (RuntimeException failure) {
            log.error("Retention run failed; it will be retried at the next interval", failure);
        }
    }
}
