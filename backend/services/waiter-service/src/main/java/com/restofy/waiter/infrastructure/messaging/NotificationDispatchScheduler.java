package com.restofy.waiter.infrastructure.messaging;

import com.restofy.waiter.application.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Background trigger of the Notification delivery queue. It only decides when a round runs; what is due and
 * how failures are handled is the dispatcher's business. Rounds never overlap (fixed delay), and a failing
 * round cannot stop the trigger.
 */
@Component
@ConditionalOnProperty(prefix = "restofy.notification.dispatcher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class NotificationDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchScheduler.class);

    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    NotificationDispatchScheduler(NotificationDispatcher dispatcher, Clock clock) {
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${restofy.notification.dispatcher.interval-ms}")
    void run() {
        try {
            dispatcher.dispatchDue(clock.instant());
        } catch (RuntimeException error) {
            log.error("Notification dispatch round failed; it will be retried on the next round", error);
        }
    }
}
