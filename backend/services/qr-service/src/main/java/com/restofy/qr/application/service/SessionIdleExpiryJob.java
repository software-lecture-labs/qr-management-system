package com.restofy.qr.application.service;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionIdleExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(SessionIdleExpiryJob.class);
    private static final int BATCH_SIZE = 100;
    private static final long SWEEP_DELAY_MILLIS = 60_000;
    private final TableSessionRepository sessions;
    private final SessionIdleExpiryService expiry;
    private final Clock clock;
    private UUID after;

    public SessionIdleExpiryJob(TableSessionRepository sessions, SessionIdleExpiryService expiry, Clock clock) {
        this.sessions = sessions;
        this.expiry = expiry;
        this.clock = clock;
    }

    public record BatchResult(int candidates, int changed, int unknownOrders, int ordersPresent, int failed) {
    }

    @Scheduled(initialDelay = SWEEP_DELAY_MILLIS, fixedDelay = SWEEP_DELAY_MILLIS)
    public synchronized BatchResult runOnce() {
        var batch = sessions.findIdleCandidates(TableSessionStatus.OPEN, GuestSessionStatus.ACTIVE,
                IdleWindow.storedTime(clock.instant()), after, PageRequest.of(0, BATCH_SIZE));
        int changed = 0;
        int unknown = 0;
        int present = 0;
        int failed = 0;
        for (var candidate : batch) {
            try {
                var result = expiry.expire(candidate.getRestaurantId(), candidate.getId());
                switch (result.outcome()) {
                    case CHANGED -> changed++;
                    case UNKNOWN_ORDERS -> unknown++;
                    case ORDERS_PRESENT -> present++;
                    default -> { /* Fresh state is no longer due or has already changed. */ }
                }
            } catch (RuntimeException exception) {
                failed++;
                // Do not log tokens, session IDs, SQL or private exception messages.
                LOG.warn("Idle expiry candidate failed with {}; a later sweep will retry safely.", exception.getClass().getSimpleName());
            }
        }
        // Keyset continuation prevents a full first batch of UNKNOWN rows from
        // starving later candidates. A restart simply scans again, idempotently.
        after = batch.size() == BATCH_SIZE ? batch.getLast().getId() : null;
        LOG.debug("Idle sweep candidates={}, changed={}, unknown={}, present={}, failed={}",
                batch.size(), changed, unknown, present, failed);
        return new BatchResult(batch.size(), changed, unknown, present, failed);
    }
}
