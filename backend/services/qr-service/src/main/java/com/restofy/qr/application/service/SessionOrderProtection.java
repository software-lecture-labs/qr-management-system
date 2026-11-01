package com.restofy.qr.application.service;

import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.entity.GuestSession;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fetches read authority outside QR locks. This is not a persistent order/finance projection or expiry permit. */
@Service
public class SessionOrderProtection {
    private final OrderSessionAccess orders;
    private final Clock clock;
    public SessionOrderProtection(OrderSessionAccess orders, Clock clock) { this.orders = orders; this.clock = clock; }

    @Transactional(propagation = Propagation.NEVER)
    public OrderSessionState inspect(UUID restaurantId, UUID sessionId) {
        var state = orders.inspect(restaurantId, sessionId);
        return state != null && state.usableFor(restaurantId, sessionId, clock.instant())
                ? state : OrderSessionState.unknown(restaurantId, sessionId);
    }

    public boolean current(OrderSessionState state, UUID restaurantId, UUID sessionId, Instant now) {
        return state != null && state.usableFor(restaurantId, sessionId, now);
    }

    public boolean protectsMain(OrderSessionState state, TableSession main, Instant now) {
        return current(state, main.getRestaurantId(), main.getId(), now) && state.protectsMain();
    }

    public boolean protectsGuest(OrderSessionState state, TableSession main, UUID guestId, Instant now) {
        return current(state, main.getRestaurantId(), main.getId(), now) && state.protectsGuest(guestId);
    }

    public boolean mainCancellationWindow(OrderSessionState state, TableSession main, Instant now) {
        return current(state, main.getRestaurantId(), main.getId(), now) && state.mainCancellationAt() != null
                && IdleWindow.deadline(state.mainCancellationAt().isAfter(main.getLastActivityAt())
                    ? state.mainCancellationAt() : main.getLastActivityAt()).isAfter(now);
    }

    public boolean guestCancellationWindow(OrderSessionState state, TableSession main, GuestSession guest, Instant now) {
        if (!current(state, main.getRestaurantId(), main.getId(), now)) { return false; }
        var released = state.cancellationReleases().get(guest.getId());
        return released != null && IdleWindow.deadline(released.isAfter(guest.getLastActivityAt())
                ? released : guest.getLastActivityAt()).isAfter(now);
    }

    public void cancellationDeadline(OrderSessionState state, TableSession main, GuestSession guest, Instant now) {
        if (!current(state, main.getRestaurantId(), main.getId(), now)) { return; }
        if (state.mainCancellationAt() != null) { main.restartIdleAfterCancellation(state.mainCancellationAt(), now, true); }
        if (guest != null && state.cancellationReleases().containsKey(guest.getId())) {
            guest.restartIdleAfterCancellation(state.cancellationReleases().get(guest.getId()), now, true);
        }
    }
}
