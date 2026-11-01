package com.restofy.order.infrastructure.client.mock;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.QrSessionPort;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOCK of the QR Service, active only in the {@code dev} and {@code test} profiles. It is not a real integration:
 * it answers from fixed in-memory fixtures. Any other profile gets {@link UnavailableQrSessionAdapter}.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class InMemoryQrSessionAdapter implements QrSessionPort {

    private final Map<String, GuestSessionContext> sessions = new ConcurrentHashMap<>();

    @PostConstruct
    void announce() {
        reset();
        log.warn("MOCK QR session adapter is active (dev/test profile only). This is NOT the real QR Service.");
    }

    @Override
    public Optional<GuestSessionContext> validateGuestSession(String guestSessionToken) {
        return Optional.ofNullable(guestSessionToken).map(sessions::get);
    }

    @Override
    public Optional<SessionState> currentSessionState(UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        return sessions.values().stream()
                .filter(context -> context.restaurantId().equals(restaurantId)
                        && context.tableSessionId().equals(tableSessionId)
                        && context.guestSessionId().equals(guestSessionId))
                .map(GuestSessionContext::state)
                .findFirst();
    }

    public void register(String token, GuestSessionContext context) {
        sessions.put(token, context);
    }

    public void unregister(String token) {
        sessions.remove(token);
    }

    /** Restores the default fixtures. */
    public final void reset() {
        sessions.clear();
        sessions.put(DevFixtures.TOKEN_A1, DevFixtures.guestA1(SessionState.ACTIVE));
        sessions.put(DevFixtures.TOKEN_A2, DevFixtures.guestA2(SessionState.ACTIVE));
        sessions.put(DevFixtures.TOKEN_B1, DevFixtures.guestB1(SessionState.ACTIVE));
    }
}
