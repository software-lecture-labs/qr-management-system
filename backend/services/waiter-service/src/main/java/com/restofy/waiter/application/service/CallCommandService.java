package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ActorType;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.CallRateLimitedException;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.infrastructure.config.CallRateLimitProperties;
import com.restofy.waiter.infrastructure.persistence.repository.CallLedger;
import com.restofy.waiter.infrastructure.persistence.repository.CallLedger.IdempotencyRecord;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every change of a call. Waiter is the only writer of calls; the guest's raise and the staff's claim and
 * resolve are all local, short database transactions that commit the call change, its audit event, the
 * delivery row for Notification and the idempotency record together. Nothing here calls another service, so a
 * Notification outage can neither fail nor undo a command.
 *
 * <p>Concurrency is settled by the database, not by checks alone: the call's version column rejects a stale
 * write, a unique index allows one active call per guest and type, and a unique idempotency key lets exactly
 * one of two identical requests do the work. A request that loses such a race is run again, which turns
 * "same key" into a replay and any other loser into a clean 409.
 */
@Service
public class CallCommandService {

    private static final int ATTEMPTS = 3;
    private static final int OK = HttpStatus.OK.value();
    private static final int CREATED = HttpStatus.CREATED.value();
    /** Operation names and actor scopes are stored with the idempotency records: never change their values. */
    private static final String CREATE = "CREATE";
    private static final String CLAIM = "CLAIM";
    private static final String RESOLVE = "RESOLVE";
    private static final String GUEST_SCOPE = "GUEST:";
    private static final String STAFF_SCOPE = "STAFF:";

    /** Result of a command; {@code httpStatus} is 201 only when a new call was created. */
    public record Outcome(ServiceRequest call, int httpStatus) {
    }

    private final ServiceRequestRepository calls;
    private final CallLedger ledger;
    private final CallEvents events;
    private final TableCommandRepository tableCommands;
    private final CallRateLimitProperties rateLimit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CallCommandService(ServiceRequestRepository calls, CallLedger ledger, CallEvents events,
                              TableCommandRepository tableCommands, CallRateLimitProperties rateLimit,
                              TransactionTemplate transaction, Clock clock) {
        this.calls = calls;
        this.ledger = ledger;
        this.events = events;
        this.tableCommands = tableCommands;
        this.rateLimit = rateLimit;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Raises a call for a verified guest. An active call of the same guest and type is returned instead of a
     * second one (200); calls of other guests of the same table are never merged.
     */
    public Outcome create(AuthenticatedGuest guest, ServiceRequestType type, String rawMessage, String idempotencyKey) {
        AuthenticatedGuest verified = AuthenticatedGuest.require(guest);
        String message = CallMessage.normalize(rawMessage);
        String key = IdempotencyKeys.require(idempotencyKey);
        String scope = GUEST_SCOPE + verified.guestSessionId();
        String digest = IdempotencyKeys.digest(CREATE, type, message);
        return atomically(() -> {
            Optional<IdempotencyRecord> prior = ledger.findIdempotency(verified.restaurantId(), scope, CREATE, key);
            if (prior.isPresent()) return replay(verified.restaurantId(), prior.get(), digest);
            // QR already refused guests of a closed session; this is the second line for a session whose closure
            // QR confirmed to Waiter, so no new call can attach itself to it.
            if (tableCommands.existsByRestaurantIdAndTableSessionIdAndTypeAndStatus(verified.restaurantId(),
                    verified.tableSessionId(), TableCommandType.CLOSE, TableCommandStatus.COMPLETED)) {
                throw new CallConflictException("SESSION_NOT_ACTIVE", "The table session is closed");
            }
            Instant now = now();
            Optional<ServiceRequest> active = calls.findFirstByRestaurantIdAndGuestSessionIdAndRequestTypeAndStatusIn(
                    verified.restaurantId(), verified.guestSessionId(), type, ServiceRequestStatus.UNRESOLVED);
            if (active.isPresent()) {
                ledger.recordIdempotency(verified.restaurantId(), scope, CREATE, key, digest, active.get().getId(), OK, now);
                return new Outcome(active.get(), OK);
            }
            enforceRateLimit(verified, now);
            ServiceRequest call = calls.saveAndFlush(ServiceRequest.open(UUID.randomUUID(), verified.restaurantId(),
                    verified.tableId(), verified.tableSessionId(), verified.guestSessionId(), type, message, now));
            record(call, CallEventType.CREATED, null, ActorType.GUEST, verified.guestSessionId(), now);
            ledger.recordIdempotency(verified.restaurantId(), scope, CREATE, key, digest, call.getId(), CREATED, now);
            return new Outcome(call, CREATED);
        });
    }

    /**
     * A guest session may raise only a few new calls per window, so one phone cannot flood the staff. The count
     * is read inside the command's transaction; two truly simultaneous requests of the same guest can overshoot
     * the limit by one, which is acceptable for a flood guard (the one-active-call-per-type index still holds).
     */
    private void enforceRateLimit(AuthenticatedGuest guest, Instant now) {
        Instant since = now.minus(rateLimit.window());
        long recent = calls.countByRestaurantIdAndGuestSessionIdAndCreatedAtAfter(guest.restaurantId(),
                guest.guestSessionId(), since);
        if (recent < rateLimit.maxNewCalls()) return;
        long retryAfter = calls.findFirstByRestaurantIdAndGuestSessionIdAndCreatedAtAfterOrderByCreatedAtAsc(
                        guest.restaurantId(), guest.guestSessionId(), since)
                .map(oldest -> Duration.between(now, oldest.getCreatedAt().plus(rateLimit.window())).toSeconds() + 1)
                .orElse(rateLimit.windowSeconds());
        throw new CallRateLimitedException(Math.max(1, retryAfter));
    }

    /** OPEN → CLAIMED. Optional step; a call that is already claimed or resolved is a conflict, never overwritten. */
    public Outcome claim(AuthenticatedStaff staff, UUID callId, long expectedVersion, String idempotencyKey) {
        return change(staff, callId, expectedVersion, idempotencyKey, CLAIM, CallEventType.CLAIMED);
    }

    /** OPEN or CLAIMED → RESOLVED by any waiter of the restaurant; the resolver and time are recorded once. */
    public Outcome resolve(AuthenticatedStaff staff, UUID callId, long expectedVersion, String idempotencyKey) {
        return change(staff, callId, expectedVersion, idempotencyKey, RESOLVE, CallEventType.RESOLVED);
    }

    private Outcome change(AuthenticatedStaff staff, UUID callId, long expectedVersion, String idempotencyKey,
                           String operation, CallEventType eventType) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        String key = IdempotencyKeys.require(idempotencyKey);
        String scope = STAFF_SCOPE + staff.staffUserId();
        String digest = IdempotencyKeys.digest(operation, callId, expectedVersion);
        return atomically(() -> {
            Optional<IdempotencyRecord> prior = ledger.findIdempotency(restaurantId, scope, operation, key);
            if (prior.isPresent()) return replay(restaurantId, prior.get(), digest);
            ServiceRequest call = calls.findByIdAndRestaurantId(callId, restaurantId)
                    .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Call not found"));
            if (call.getVersion() != expectedVersion) {
                throw new CallConflictException("VERSION_CONFLICT",
                        "The call was changed by someone else; reload it before trying again");
            }
            Instant now = now();
            ServiceRequestStatus from = call.getStatus();
            if (eventType == CallEventType.CLAIMED) call.claim(staff.staffUserId(), now);
            else call.resolve(staff.staffUserId(), now);
            calls.saveAndFlush(call);
            record(call, eventType, from, ActorType.STAFF, staff.staffUserId(), now);
            ledger.recordIdempotency(restaurantId, scope, operation, key, digest, call.getId(), OK, now);
            return new Outcome(call, OK);
        });
    }

    /**
     * A repeat of a finished command returns the first outcome again (with the call's current state) and does
     * nothing else. The same key with another request is a different, conflicting command.
     */
    private Outcome replay(UUID restaurantId, IdempotencyRecord prior, String digest) {
        if (!prior.requestDigest().equals(digest)) {
            throw new CallConflictException("IDEMPOTENCY_KEY_REUSED",
                    "The Idempotency-Key was already used for a different request");
        }
        ServiceRequest call = calls.findByIdAndRestaurantId(prior.serviceRequestId(), restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Call not found"));
        return new Outcome(call, prior.responseStatus());
    }

    private void record(ServiceRequest call, CallEventType type, ServiceRequestStatus from, ActorType actorType,
                        UUID actorId, Instant now) {
        events.record(call, type, from, actorType, actorId, now);
    }

    private <T> T atomically(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (ObjectOptimisticLockingFailureException lost) {
                if (attempt >= ATTEMPTS) {
                    throw new CallConflictException("VERSION_CONFLICT",
                            "The call was changed by someone else; reload it before trying again");
                }
            } catch (DataIntegrityViolationException raced) {
                if (attempt >= ATTEMPTS) throw raced;
            }
        }
    }

    /** Database timestamps have microsecond precision; truncating keeps the response equal to what is stored. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
