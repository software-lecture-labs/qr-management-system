package com.restofy.waiter.infrastructure.messaging;

import com.restofy.waiter.application.service.TableCommandReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Background trigger of the table command reconciliation. It only decides when a round runs; which commands are
 * due and what the answer of QR means is the reconciler's business. Rounds never overlap (fixed delay), and a
 * failing round cannot stop the trigger.
 */
@Component
@ConditionalOnProperty(prefix = "restofy.table-commands.reconciler", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class TableCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(TableCommandScheduler.class);

    private final TableCommandReconciler reconciler;
    private final Clock clock;

    TableCommandScheduler(TableCommandReconciler reconciler, Clock clock) {
        this.reconciler = reconciler;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${restofy.table-commands.reconciler.interval-ms}")
    void run() {
        try {
            reconciler.reconcileDue(clock.instant());
        } catch (RuntimeException error) {
            log.error("Table command reconciliation round failed; it will be retried on the next round", error);
        }
    }
}
