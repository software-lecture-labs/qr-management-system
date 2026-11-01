package com.restofy.qr.application.service;

import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal locked reads, not authorization or reusable order/activity permits.
 * Caller must own the mutation transaction and apply current lifecycle/authority checks.
 */
@Service
public class GuestSessionContextReader {
    private final GuestSessionRepository guests;
    private final TableSessionRepository sessions;
    private final DiningTableRepository tables;
    private final GuestTokenCodec tokens;
    private final Clock clock;

    public GuestSessionContextReader(GuestSessionRepository guests, TableSessionRepository sessions,
                                      DiningTableRepository tables, GuestTokenCodec tokens, Clock clock) {
        this.guests = guests;
        this.sessions = sessions;
        this.tables = tables;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Immutable token location only; mutable lifecycle authority is checked again under the parent lock. */
    @Transactional(readOnly = true)
    public Identity locate(String token) {
        String digest;
        try { digest = tokens.digest(token); }
        catch (IllegalArgumentException _) { throw new InvalidGuestSessionException(); }
        var location = guests.locateByDigest(digest).orElseThrow(InvalidGuestSessionException::new);
        return new Identity(location.getRestaurantId(), location.getTableSessionId(), location.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedGuest lockIdentity(String token, UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        String digest;
        try { digest = tokens.digest(token); }
        catch (IllegalArgumentException _) { throw new InvalidGuestSessionException(); }
        var location = guests.locateByDigest(digest).orElseThrow(InvalidGuestSessionException::new);
        if (!location.getRestaurantId().equals(restaurantId) || !location.getTableSessionId().equals(tableSessionId)
                || !location.getId().equals(guestSessionId)) { throw new InvalidGuestSessionException(); }
        var parent = loadSession(restaurantId, tableSessionId);
        var guest = guests.findByRestaurantIdAndTableSessionIdAndId(restaurantId, tableSessionId, guestSessionId)
                .orElseThrow(InvalidGuestSessionException::new);
        if (!guest.getTokenDigest().equals(digest)) { throw new InvalidGuestSessionException(); }
        return new LockedGuest(parent, guest);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedSession lockSession(UUID restaurantId, UUID tableSessionId) { return loadSession(restaurantId, tableSessionId); }

    private LockedSession loadSession(UUID restaurantId, UUID tableSessionId) {
        // Only immutable IDs before the lock, fresh mutable state after it.
        UUID tableId = sessions.locateTableId(restaurantId, tableSessionId).orElseThrow(InvalidGuestSessionException::new);
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(InvalidGuestSessionException::new);
        var main = sessions.findByRestaurantIdAndId(restaurantId, tableSessionId).orElseThrow(InvalidGuestSessionException::new);
        return new LockedSession(table, main, clock.instant());
    }

    public record LockedSession(DiningTable table, TableSession main, Instant checkedAt) { }
    public record LockedGuest(LockedSession parent, GuestSession guest) { }
    public record Identity(UUID restaurantId, UUID sessionId, UUID guestId) { }
}
