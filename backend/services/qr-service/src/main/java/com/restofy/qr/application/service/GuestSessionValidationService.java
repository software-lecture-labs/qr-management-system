package com.restofy.qr.application.service;

import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GuestSessionValidationService {
    private final GuestSessionContextReader contexts;
    private final SessionOrderProtection protection;
    private final TransactionTemplate writes;
    private final TransactionTemplate mandatory;

    public GuestSessionValidationService(GuestSessionContextReader contexts, SessionOrderProtection protection,
                                         PlatformTransactionManager manager) {
        this.contexts = contexts; this.protection = protection; writes = new TransactionTemplate(manager);
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public record Prepared(GuestSessionContextReader.Identity identity, OrderSessionState state) { }

    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(String token) {
        return resolve(token);
    }

    private Prepared resolve(String token) {
        var identity = contexts.locate(token);
        return new Prepared(identity, protection.inspect(identity.restaurantId(), identity.sessionId()));
    }

    /** Token-only Order contract. No activity write and no reusable checkout permit. */
    @Transactional(propagation = Propagation.NEVER)
    public ValidatedGuestContext validate(String token) {
        var prepared = resolve(token);
        return writes.execute(status -> validateLocked(token, prepared));
    }

    @Transactional(propagation = Propagation.NEVER)
    public ValidatedGuestContext validate(String token, UUID restaurantId, UUID sessionId, UUID guestId) {
        var prepared = resolve(token);
        requireExpected(prepared, restaurantId, sessionId, guestId);
        return writes.execute(status -> validateLocked(token, prepared));
    }

    public void requireExpected(Prepared prepared, UUID restaurantId, UUID sessionId, UUID guestId) {
        var identity = prepared.identity();
        if (!identity.restaurantId().equals(restaurantId) || !identity.sessionId().equals(sessionId)
                || !identity.guestId().equals(guestId)) { throw new InvalidGuestSessionException(); }
    }

    public ValidatedGuestContext validateLocked(String token, Prepared prepared) {
        return mandatory.execute(status -> validateInTransaction(token, prepared));
    }

    private ValidatedGuestContext validateInTransaction(String token, Prepared prepared) {
        var identity = prepared.identity();
        var locked = contexts.lockIdentity(token, identity.restaurantId(), identity.sessionId(), identity.guestId());
        var main = locked.parent().main();
        var table = locked.parent().table();
        var guest = locked.guest();
        var now = locked.parent().checkedAt();
        // Validation is deliberately read-only with respect to activity and cancellation deadlines.
        boolean mainHeld = protection.protectsMain(prepared.state(), main, now);
        boolean guestHeld = protection.protectsGuest(prepared.state(), main, guest.getId(), now);
        if (!table.isActive() || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || !main.isOpenAt(now, mainHeld || protection.mainCancellationWindow(prepared.state(), main, now))
                || !guest.isUsableAt(now, guestHeld || protection.guestCancellationWindow(prepared.state(), main, guest, now))) {
            throw new InvalidGuestSessionException();
        }
        return new ValidatedGuestContext(identity.restaurantId(), table.getId(), main.getId(), guest.getId(), now);
    }

    public record ValidatedGuestContext(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId, Instant checkedAt) { }
}
