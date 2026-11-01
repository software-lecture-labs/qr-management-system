package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.infrastructure.client.notification.NotificationClient;
import com.restofy.waiter.infrastructure.config.NotificationDispatcherProperties;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries.DueDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Sends the queued call events to Notification. The queue, not memory, holds what is left to do, so an outage,
 * a lost answer or a restart only delays delivery. Every resend carries the same stored body and event id, so
 * Notification can recognise a repeat; a resolved call is never reopened because a send failed, since nothing
 * here touches the call itself.
 *
 * <p>A transport timeout does not tell whether Notification applied the event, which is exactly why the same
 * event id is resent. After {@code maxAttempts} the event is parked as DEAD and stays visible in the API; it
 * is not retried forever and not silently dropped.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Counters of one dispatch round, mainly for tests and logs. */
    public record Round(int attempted, int delivered, int retrying, int dead) {
    }

    private final NotificationDeliveries deliveries;
    private final NotificationClient client;
    private final NotificationDispatcherProperties policy;

    public NotificationDispatcher(NotificationDeliveries deliveries, NotificationClient client,
                                  NotificationDispatcherProperties policy) {
        this.deliveries = deliveries;
        this.client = client;
        this.policy = policy;
    }

    public Round dispatchDue(Instant now) {
        List<DueDelivery> due = deliveries.leaseDue(now, policy.batchSize(), policy.lease());
        int delivered = 0;
        int retrying = 0;
        int dead = 0;
        for (DueDelivery delivery : due) {
            try {
                client.publish(delivery.eventId(), delivery.payload());
                deliveries.markDelivered(delivery.eventId(), now);
                delivered++;
            } catch (DependencyException failure) {
                if (fail(delivery, failure.failure().name(), now)) dead++;
                else retrying++;
            } catch (RuntimeException unexpected) {
                log.warn("Unexpected error while delivering event {}", delivery.eventId(), unexpected);
                if (fail(delivery, "INTERNAL_ERROR", now)) dead++;
                else retrying++;
            }
        }
        return new Round(due.size(), delivered, retrying, dead);
    }

    /** Returns true when the event was parked as DEAD, false when another attempt is scheduled. */
    private boolean fail(DueDelivery delivery, String errorCode, Instant now) {
        if (delivery.attemptCount() >= policy.maxAttempts()) {
            deliveries.markFailed(delivery.eventId(), errorCode, null, now);
            log.error("Notification delivery of event {} (call {}, version {}) is DEAD after {} attempts: {}",
                    delivery.eventId(), delivery.serviceRequestId(), delivery.callVersion(), delivery.attemptCount(), errorCode);
            return true;
        }
        Instant next = now.plus(policy.backoffAfter(delivery.attemptCount()));
        deliveries.markFailed(delivery.eventId(), errorCode, next, now);
        log.warn("Notification delivery of event {} (call {}, version {}) failed on attempt {}: {}; next attempt at {}",
                delivery.eventId(), delivery.serviceRequestId(), delivery.callVersion(), delivery.attemptCount(), errorCode, next);
        return false;
    }
}
