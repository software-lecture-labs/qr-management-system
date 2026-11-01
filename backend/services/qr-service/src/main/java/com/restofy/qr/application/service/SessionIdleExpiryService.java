package com.restofy.qr.application.service;

import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Idle expiry uses the SAME fenced durable lifecycle protocol as settled closure, never a read-only NO_ORDERS flag. */
@Service
public class SessionIdleExpiryService {
    public enum Outcome { CHANGED, NOT_DUE, MISSING_OR_CLOSED, INCONSISTENT, UNKNOWN_ORDERS, ORDERS_PRESENT }
    public record ExpiryResult(Outcome outcome, boolean mainExpired, int guestsExpired) { }
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final TableSessionClosureService lifecycle;
    private final Clock clock;
    private final SessionOrderProtection protection;
    public SessionIdleExpiryService(TableSessionRepository sessions, GuestSessionRepository guests,
                                      TableSessionClosureService lifecycle, Clock clock, SessionOrderProtection protection) {
        this.sessions = sessions; this.guests = guests; this.lifecycle = lifecycle; this.clock = clock; this.protection = protection;
    }

    @Transactional(propagation = Propagation.NEVER)
    public ExpiryResult expire(UUID restaurantId, UUID sessionId) {
        var found = sessions.findByRestaurantIdAndId(restaurantId, sessionId);
        if (found.isEmpty() || found.orElseThrow().getStatus() != TableSessionStatus.OPEN) {
            return new ExpiryResult(Outcome.MISSING_OR_CLOSED, false, 0);
        }
        var main = found.orElseThrow();
        var now = clock.instant();
        if (main.getOpenedAt().isAfter(now) || main.getLastActivityAt().isAfter(now)) { return new ExpiryResult(Outcome.INCONSISTENT, false, 0); }
        var active = guests.findByRestaurantIdAndTableSessionIdAndStatus(restaurantId, sessionId, GuestSessionStatus.ACTIVE);
        if (inconsistentGuests(main, active, now)) { return new ExpiryResult(Outcome.INCONSISTENT, false, 0); }
        boolean due = main.isIdleDueAt(now) || active.stream().anyMatch(guest -> guest.getExpiresAt() != null && !guest.getExpiresAt().isAfter(now));
        if (!due) { return new ExpiryResult(Outcome.NOT_DUE, false, 0); }
        var knowledge = protection.inspect(restaurantId, sessionId);
        var view = lifecycle.evaluate(restaurantId, sessionId, Purpose.IDLE);
        if (view == null) { return unchanged(knowledge, main, active, now); }
        return outcome(view);
    }

    private static ExpiryResult outcome(OperationView view) {
        if (view.stage() == Stage.REQUESTED || view.stage() == Stage.ABORTING) {
            return new ExpiryResult(Outcome.UNKNOWN_ORDERS, false, 0);
        }
        if (view.stage() == Stage.ABORTED) { return new ExpiryResult(Outcome.ORDERS_PRESENT, false, 0); }
        var result = view.result();
        boolean expired = result != null && result.effect() == Effect.IDLE_MAIN;
        int count = result == null ? 0 : result.expiredGuests();
        return new ExpiryResult(expired || count > 0 ? Outcome.CHANGED : Outcome.ORDERS_PRESENT, expired, count);
    }

    private static boolean inconsistentGuests(TableSession main, List<GuestSession> active, Instant now) {
        return active.stream().anyMatch(guest -> guest.getJoinedAt().isAfter(now) || guest.getLastActivityAt().isAfter(now)
                || guest.getLastActivityAt().isAfter(main.getLastActivityAt()));
    }

    private ExpiryResult unchanged(OrderSessionState knowledge, TableSession main, List<GuestSession> active, Instant now) {
        if (knowledge.protectsMain()) { return new ExpiryResult(Outcome.ORDERS_PRESENT, false, 0); }
        if (knowledge.knowledge() != OrderSessionState.Knowledge.UNKNOWN) {
            // The short writer may have materialised a fixed cancellation deadline. The outer read context can
            // still contain its older entity snapshot; evaluate that same verified window without trusting a stale reread.
            boolean mainDue = main.isIdleDueAt(now) && !protection.mainCancellationWindow(knowledge, main, now);
            boolean guestDue = active.stream().anyMatch(guest -> guest.getExpiresAt() != null && !guest.getExpiresAt().isAfter(now)
                    && !protection.guestCancellationWindow(knowledge, main, guest, now));
            if (!mainDue && !guestDue) {
                return new ExpiryResult(Outcome.NOT_DUE, false, 0);
            }
        }
        return new ExpiryResult(Outcome.UNKNOWN_ORDERS, false, 0);
    }
}
