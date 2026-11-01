package com.restofy.qr.application.service;

import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded restart-safe sweep: pending intents first, then Order's settled-session hints. Never retries payments. */
@Component
public class SessionClosureRetryJob {
    private static final Logger LOG = LoggerFactory.getLogger(SessionClosureRetryJob.class);
    private static final int BATCH_SIZE = 100;
    private static final long SWEEP_DELAY_MILLIS = 60_000;
    private final SessionTransitionOperationRepository operations;
    private final TableSessionRepository sessions;
    private final TableSessionClosureService lifecycle;
    private UUID afterOperation;
    private UUID afterSession;

    public SessionClosureRetryJob(SessionTransitionOperationRepository operations, TableSessionRepository sessions,
                                   TableSessionClosureService lifecycle) {
        this.operations = operations; this.sessions = sessions; this.lifecycle = lifecycle;
    }

    public record BatchResult(int pending, int candidates, int failed) { }

    @Scheduled(initialDelay = SWEEP_DELAY_MILLIS, fixedDelay = SWEEP_DELAY_MILLIS)
    public synchronized BatchResult runOnce() {
        var page = PageRequest.of(0, BATCH_SIZE);
        var pending = operations.findPending(List.of(Stage.REQUESTED, Stage.APPLIED, Stage.ABORTING), afterOperation, page);
        int failures = 0;
        for (var operation : pending) {
            try { lifecycle.resume(operation.getRestaurantId(), operation.getId()); }
            catch (RuntimeException failure) { failures++; report(failure); }
        }
        afterOperation = pending.size() == BATCH_SIZE ? pending.getLast().getId() : null;
        var open = sessions.findLifecycleCandidates(TableSessionStatus.OPEN, afterSession, page);
        for (var candidate : open) {
            try { lifecycle.evaluate(candidate.getRestaurantId(), candidate.getId(), Purpose.SETTLED); }
            catch (RuntimeException failure) { failures++; report(failure); }
        }
        afterSession = open.size() == BATCH_SIZE ? open.getLast().getId() : null;
        return new BatchResult(pending.size(), open.size(), failures);
    }

    private static void report(RuntimeException failure) {
        LOG.warn("event=QR_LIFECYCLE_RETRY type={}", failure.getClass().getSimpleName());
    }
}
