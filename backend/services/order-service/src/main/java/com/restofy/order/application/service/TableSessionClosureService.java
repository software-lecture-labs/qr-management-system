package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.ConfirmClosureRequest;
import com.restofy.order.api.dto.request.PrepareClosureRequest;
import com.restofy.order.api.dto.response.ClosureResponse;
import com.restofy.order.api.dto.response.OrderSessionStateResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.closure.ClosureBlocker;
import com.restofy.order.domain.closure.ClosureEffect;
import com.restofy.order.domain.closure.ClosureEligibility;
import com.restofy.order.domain.closure.ClosureOutcome;
import com.restofy.order.domain.closure.ClosurePurpose;
import com.restofy.order.domain.closure.OrderClosureFacts;
import com.restofy.order.domain.closure.OrderSessionScope;
import com.restofy.order.domain.closure.SourceStatus;
import com.restofy.order.domain.enums.ClosureConfirmation;
import com.restofy.order.domain.enums.ClosureStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore.Closure;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore.Drift;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The lifecycle of a table session, from the Order Service's side (ADR-QR-001 K1-K9): closing a settled session and
 * expiring an idle one. The QR Service owns the table and its sessions and does the change; the Order Service owns the
 * orders and decides from ALL of them whether the change is allowed, so that the QR Service keeps no second copy of
 * orders or money.
 *
 * <ol>
 *   <li><b>Prepare</b>: under the table session's exclusive lock (which waits for every checkout in flight) the
 *       Order Service decides. An authorised operation is stored (PREPARED) and keeps orders out (checkout refuses
 *       everybody, or only the guests that are about to expire); anything else changes nothing and orders continue. The
 *       answer is a permit with a short validity: asking again with the same operation re-checks the orders and renews
 *       it, or withdraws it (BLOCKED) when the orders changed before the QR Service applied anything.</li>
 *   <li><b>Confirm</b>: the QR Service reports CLOSED (it applied the change) or NOT_CLOSED (it did not, which aborts).
 *       CLOSED is ALWAYS accepted: the change is irreversible there, so a participant that said "ready" cannot refuse
 *       the final decision. If the orders moved since the preparation that is written down for people to reconcile.
 *       NOT_CLOSED releases the hold, and for an operation that holds nothing it is a plain, safe "nothing to abort".
 *       Nothing is ever released by time: a timeout does not prove the change failed.</li>
 * </ol>
 * Every step is idempotent per operation id, so a lost response is answered by asking again. This does not make the QR
 * and Order databases atomic: the two services agree through these durable records and the confirmation.
 */
@Slf4j
@Service
public class TableSessionClosureService {

    /** How long a permit may be used before the QR Service has to ask again (and the orders are checked again). */
    static final Duration PERMIT_VALIDITY = Duration.ofMinutes(1);

    private static final String NOT_PREPARED = "NOT_PREPARED";
    private static final String NOT_STARTED = "NOT_STARTED";

    /** What the orders say about a stored operation right now. */
    private record Judgement(boolean valid, ClosureOutcome outcome, Set<ClosureBlocker> blockers, String fingerprint) {
    }

    /** What the Order Service decided for a new command. */
    private record Decision(SourceStatus status, ClosureOutcome outcome, ClosureEffect effect, Set<UUID> idleGuests,
                            Set<ClosureBlocker> blockers, String fingerprint, int orderCount) {

        boolean authorised() {
            return status == SourceStatus.AUTHORISED;
        }
    }

    private final TableSessionClosureStore closures;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public TableSessionClosureService(TableSessionClosureStore closures, Clock clock,
                                      PlatformTransactionManager txManager) {
        this.closures = closures;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /**
     * Called by checkout inside its transaction, as the first thing it does. Takes the shared ordering lock and refuses
     * an order from a guest that a held operation keeps out (everybody for a closure or a main-session expiry, only
     * the named guests for a guest expiry); nothing has been written yet when it throws.
     */
    public void requireOpenForCheckout(UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        closures.lockForCheckout(restaurantId, tableSessionId);
        if (closures.findLive(restaurantId, tableSessionId).filter(live -> live.fences(guestSessionId)).isPresent()) {
            throw new BusinessException(ErrorCode.TABLE_SESSION_CLOSING,
                    "The table is being closed; an order cannot be placed for this table session");
        }
    }

    public ClosureResponse prepare(ServiceActor service, UUID tableSessionId, PrepareClosureRequest request) {
        UUID restaurantId = request.restaurantId();
        return writeTx.execute(status -> {
            closures.lockForClosure(restaurantId, tableSessionId);
            Instant now = now();
            List<OrderClosureFacts> facts = closures.loadFacts(restaurantId, tableSessionId);
            Optional<Closure> known = closures.findByOperation(request.operationId());
            if (known.isPresent()) {
                return authority(requireSameCommand(known.get(), request, tableSessionId), facts, now);
            }
            closures.findLive(restaurantId, tableSessionId).ifPresent(live -> {
                throw new BusinessException(ErrorCode.CLOSURE_IN_PROGRESS,
                        "Another operation of this table session is already " + live.status().name().toLowerCase(),
                        Map.of("operationId", live.operationId(), "state", live.status()));
            });
            Decision decision = decide(request, facts);
            if (!decision.authorised()) {
                log.info("Table session operation not authorised: service={} restaurantId={} tableSessionId={}"
                                + " purpose={} status={} blockers={}", service.name(), restaurantId, tableSessionId,
                        request.purpose(), decision.status(), decision.blockers());
                return notPrepared(request, tableSessionId, decision, now);
            }
            closures.insertPrepared(new TableSessionClosureStore.Prepared(request.operationId(), restaurantId,
                    tableSessionId, request.purpose(), decision.effect(), request.commandDigest(),
                    decision.idleGuests(), decision.orderCount(), decision.fingerprint()), now);
            log.info("Table session operation prepared: service={} operationId={} restaurantId={} tableSessionId={}"
                            + " purpose={} effect={} orders={}", service.name(), request.operationId(), restaurantId,
                    tableSessionId, request.purpose(), decision.effect(), decision.orderCount());
            return authority(closures.findByOperation(request.operationId()).orElseThrow(), facts, now);
        });
    }

    /**
     * What became of an operation, for a QR Service that lost a response or whose permit ran out: the stored operation
     * with the permit re-checked against the orders as they are now. An operation this Order Service never saw is
     * {@code NOT_STARTED}, so the QR Service knows to prepare it.
     */
    public ClosureResponse get(UUID restaurantId, UUID tableSessionId, UUID operationId, String commandDigest) {
        return readTx.execute(status -> {
            Instant now = now();
            List<OrderClosureFacts> facts = closures.loadFacts(restaurantId, tableSessionId);
            return find(restaurantId, tableSessionId, operationId)
                    .map(closure -> authority(requireSameDigest(closure, commandDigest), facts, now))
                    .orElseGet(() -> notStarted(restaurantId, tableSessionId, operationId, commandDigest, now));
        });
    }

    /** What the orders of the session mean for its idle rules; read-only, never a permit. */
    public OrderSessionStateResponse orderState(UUID restaurantId, UUID tableSessionId) {
        return readTx.execute(status -> {
            Instant now = now();
            OrderSessionScope scope = OrderSessionScope.of(closures.loadFacts(restaurantId, tableSessionId));
            return new OrderSessionStateResponse(restaurantId, tableSessionId, scope.knowledge(),
                    closures.scopeRevision(restaurantId, tableSessionId), now, now.plus(PERMIT_VALIDITY),
                    scope.protectedGuests().stream().sorted().toList(), scope.cancellationReleases(),
                    scope.mainCancellationAt());
        });
    }

    public ClosureResponse confirm(ServiceActor service, UUID tableSessionId, UUID operationId,
                                   ConfirmClosureRequest request) {
        UUID restaurantId = request.restaurantId();
        return writeTx.execute(status -> {
            closures.lockForClosure(restaurantId, tableSessionId);
            Instant now = now();
            Optional<Closure> found = find(restaurantId, tableSessionId, operationId);
            if (found.isEmpty()) {
                if (request.outcome() == ClosureConfirmation.NOT_CLOSED) {
                    // an abort of something this Order Service holds nothing for is a definitive, safe "nothing to abort"
                    return notStarted(restaurantId, tableSessionId, operationId, request.commandDigest(), now);
                }
                throw notFound();
            }
            Closure closure = requireSameDigest(found.get(), request.commandDigest());
            List<OrderClosureFacts> facts = closures.loadFacts(restaurantId, tableSessionId);
            if (request.outcome() == ClosureConfirmation.CLOSED) {
                confirmClosed(closure, request.receipt(), facts, now);
            } else {
                confirmNotClosed(closure, now);
            }
            log.info("Table session operation confirmed: service={} operationId={} restaurantId={} tableSessionId={}"
                    + " outcome={}", service.name(), operationId, restaurantId, tableSessionId, request.outcome());
            return authority(closures.findByOperation(operationId).orElseThrow(), facts, now);
        });
    }

    private void confirmClosed(Closure closure, ConfirmClosureRequest.Receipt receipt, List<OrderClosureFacts> facts,
                               Instant now) {
        switch (closure.status()) {
            case COMPLETED -> {
                // the same confirmation again: nothing to do
            }
            case RELEASED -> throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This operation was released; it cannot be completed");
            case PREPARED -> {
                if (receipt != null && receipt.effect() != closure.effect()) {
                    throw new BusinessException(ErrorCode.CLOSURE_RECEIPT_MISMATCH,
                            "The receipt names another effect than the one that was permitted",
                            Map.of("permitted", closure.effect(), "received", receipt.effect()));
                }
                // The QR Service has already applied the change and never undoes it by itself, so a participant that
                // said "ready" cannot refuse the final decision: CLOSED is always accepted. If the orders moved since
                // the preparation (in practice a money report that could only be recorded) that is written down for
                // people to reconcile, never used to leave the two services disagreeing.
                Judgement now_ = judge(closure, facts);
                closures.markCompleted(closure.operationId(), receipt == null ? null
                        : new TableSessionClosureStore.Receipt(receipt.sessionVersion(), receipt.tableVersion(),
                        receipt.completedAt(), receipt.expiredGuests()), now);
                if (!now_.valid()) {
                    closures.recordDrift(closure, now_.fingerprint(), now_.outcome(), now_.blockers(), now);
                    log.warn("Table session operation completed with SCOPE DRIFT: operationId={} restaurantId={}"
                                    + " tableSessionId={} outcome={} blockers={}", closure.operationId(),
                            closure.restaurantId(), closure.tableSessionId(), now_.outcome(), now_.blockers());
                }
            }
        }
    }

    private void confirmNotClosed(Closure closure, Instant now) {
        switch (closure.status()) {
            case RELEASED -> {
                // the same confirmation again: nothing to do
            }
            case COMPLETED -> throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This operation was completed; it cannot be released");
            case PREPARED -> closures.markReleased(closure.operationId(), now);
        }
    }

    // ---- the decision --------------------------------------------------------------------------------------------------

    private Decision decide(PrepareClosureRequest request, List<OrderClosureFacts> facts) {
        ClosureEligibility.Result eligibility = ClosureEligibility.evaluate(facts);
        if (request.purpose() == ClosurePurpose.SETTLED) {
            if (eligibility.eligible()) {
                return new Decision(SourceStatus.AUTHORISED, ClosureOutcome.ELIGIBLE, ClosureEffect.SETTLED_CLOSE,
                        Set.of(), Set.of(), eligibility.fingerprint(), eligibility.orderCount());
            }
            SourceStatus status = eligibility.outcome() == ClosureOutcome.UNKNOWN ? SourceStatus.UNKNOWN
                    : SourceStatus.BLOCKED;
            return new Decision(status, eligibility.outcome(), ClosureEffect.NONE, Set.of(), eligibility.blockers(),
                    eligibility.fingerprint(), eligibility.orderCount());
        }
        return decideIdle(request, facts, eligibility.fingerprint());
    }

    /**
     * Idle expiry: the whole session may expire only when no order protects it and its own idle window ended; otherwise
     * only the guests that are idle AND not protected by one of their orders may. Everything else is BLOCKED.
     */
    private Decision decideIdle(PrepareClosureRequest request, List<OrderClosureFacts> facts, String fingerprint) {
        if (request.requestedAt() == null || request.mainIdleExpiresAt() == null || request.guests() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "requestedAt, mainIdleExpiresAt and guests are required for an IDLE command");
        }
        OrderSessionScope scope = OrderSessionScope.of(facts);
        boolean mainIdle = !request.mainIdleExpiresAt().isAfter(request.requestedAt());
        if (!scope.protectsMain() && mainIdle) {
            return new Decision(SourceStatus.AUTHORISED, ClosureOutcome.ELIGIBLE, ClosureEffect.IDLE_MAIN, Set.of(),
                    Set.of(), fingerprint, facts.size());
        }
        Set<UUID> idleGuests = request.guests().stream()
                .filter(g -> !g.idleExpiresAt().isAfter(request.requestedAt()))
                .map(PrepareClosureRequest.IdleGuest::guestSessionId)
                .filter(id -> !scope.protectedGuests().contains(id)).collect(Collectors.toCollection(TreeSet::new));
        if (!idleGuests.isEmpty()) {
            return new Decision(SourceStatus.AUTHORISED, ClosureOutcome.ELIGIBLE, ClosureEffect.IDLE_GUESTS, idleGuests,
                    Set.of(), fingerprint, facts.size());
        }
        ClosureBlocker blocker = scope.protectsMain() ? ClosureBlocker.SESSION_PROTECTED_BY_ORDERS
                : ClosureBlocker.NOTHING_TO_EXPIRE;
        return new Decision(SourceStatus.BLOCKED, ClosureOutcome.NOT_ELIGIBLE, ClosureEffect.NONE, Set.of(),
                EnumSet.of(blocker), fingerprint, facts.size());
    }

    /** Is the stored operation still what the orders say? Settled: eligible and unchanged. Idle: still unprotected. */
    private static Judgement judge(Closure closure, List<OrderClosureFacts> facts) {
        ClosureEligibility.Result eligibility = ClosureEligibility.evaluate(facts);
        if (closure.purpose() == ClosurePurpose.SETTLED) {
            if (eligibility.eligible() && eligibility.fingerprint().equals(closure.scopeFingerprint())) {
                return new Judgement(true, ClosureOutcome.ELIGIBLE, Set.of(), eligibility.fingerprint());
            }
            Set<ClosureBlocker> blockers = eligibility.eligible() ? EnumSet.of(ClosureBlocker.SCOPE_CHANGED)
                    : eligibility.blockers();
            ClosureOutcome outcome = eligibility.eligible() ? ClosureOutcome.NOT_ELIGIBLE : eligibility.outcome();
            return new Judgement(false, outcome, blockers, eligibility.fingerprint());
        }
        OrderSessionScope scope = OrderSessionScope.of(facts);
        boolean valid = closure.effect() == ClosureEffect.IDLE_MAIN ? !scope.protectsMain()
                : closure.idleGuestIds().stream().noneMatch(scope.protectedGuests()::contains);
        return new Judgement(valid, valid ? ClosureOutcome.ELIGIBLE : ClosureOutcome.NOT_ELIGIBLE,
                valid ? Set.of() : EnumSet.of(ClosureBlocker.SESSION_PROTECTED_BY_ORDERS), eligibility.fingerprint());
    }

    // ---- the answers ---------------------------------------------------------------------------------------------------

    /** The stored operation as an authority: PREPARED is re-checked, so the permit is renewed or withdrawn. */
    private ClosureResponse authority(Closure c, List<OrderClosureFacts> facts, Instant now) {
        SourceStatus status;
        ClosureEffect effect = c.effect();
        ClosureOutcome outcome = ClosureOutcome.ELIGIBLE;
        List<ClosureBlocker> blockers = List.of();
        boolean fenced = c.status() == ClosureStatus.PREPARED || c.effect() != ClosureEffect.IDLE_GUESTS;
        switch (c.status()) {
            case PREPARED -> {
                Judgement judgement = judge(c, facts);
                if (judgement.valid()) {
                    status = SourceStatus.AUTHORISED;
                } else {
                    status = judgement.outcome() == ClosureOutcome.UNKNOWN ? SourceStatus.UNKNOWN
                            : SourceStatus.BLOCKED;
                    effect = ClosureEffect.NONE;
                    outcome = judgement.outcome();
                    blockers = sorted(judgement.blockers());
                }
            }
            case COMPLETED -> status = SourceStatus.AUTHORISED;
            default -> {
                status = SourceStatus.BLOCKED;
                effect = ClosureEffect.NONE;
                fenced = false;
            }
        }
        Optional<Drift> drift = c.status() == ClosureStatus.COMPLETED ? closures.findDrift(c.operationId())
                : Optional.empty();
        return new ClosureResponse(c.operationId(), c.restaurantId(), c.tableSessionId(), c.purpose(),
                c.commandDigest(), c.status().name(), status, effect, outcome, blockers, c.orderCount(), c.scopeFingerprint(),
                closures.scopeRevision(c.restaurantId(), c.tableSessionId()), now, now.plus(PERMIT_VALIDITY), true,
                fenced, List.copyOf(c.idleGuestIds()), c.preparedAt(), c.completedAt(), c.releasedAt(),
                drift.isPresent(), drift.map(Drift::blockers).orElse(List.of()));
    }

    /** A command that was not authorised: nothing is stored or held, and the permit says why. */
    private ClosureResponse notPrepared(PrepareClosureRequest request, UUID tableSessionId, Decision decision,
                                        Instant now) {
        return new ClosureResponse(request.operationId(), request.restaurantId(), tableSessionId, request.purpose(),
                request.commandDigest(), NOT_PREPARED, decision.status(), ClosureEffect.NONE, decision.outcome(), sorted(decision.blockers()),
                decision.orderCount(), decision.fingerprint(),
                closures.scopeRevision(request.restaurantId(), tableSessionId), now, now.plus(PERMIT_VALIDITY), true,
                false, List.of(), null, null, null, false, List.of());
    }

    /**
     * An operation the Order Service never saw. Still a complete, current answer, so the QR Service goes on to prepare.
     * The digest is echoed only when the question carried one.
     */
    private ClosureResponse notStarted(UUID restaurantId, UUID tableSessionId, UUID operationId, String commandDigest,
                                       Instant now) {
        return new ClosureResponse(operationId, restaurantId, tableSessionId, null, commandDigest, NOT_STARTED,
                SourceStatus.NOT_STARTED, ClosureEffect.NONE, ClosureOutcome.UNKNOWN, List.of(), 0, null,
                closures.scopeRevision(restaurantId, tableSessionId), now, now.plus(PERMIT_VALIDITY), true, false,
                List.of(), null, null, null, false, List.of());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The operation with this id, if it belongs to this restaurant and table session (anything else is not found). */
    private Optional<Closure> find(UUID restaurantId, UUID tableSessionId, UUID operationId) {
        return closures.findByOperation(operationId)
                .filter(c -> c.restaurantId().equals(restaurantId) && c.tableSessionId().equals(tableSessionId));
    }

    /** A known operation id must always mean the same command: same restaurant, session, purpose and digest. */
    private static Closure requireSameCommand(Closure closure, PrepareClosureRequest request, UUID tableSessionId) {
        if (!closure.restaurantId().equals(request.restaurantId()) || !closure.tableSessionId().equals(tableSessionId)
                || closure.purpose() != request.purpose() || !closure.commandDigest().equals(request.commandDigest())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This operation id was already used for another command");
        }
        return closure;
    }

    /**
     * A question or confirmation that names the command's digest must name the one the operation was prepared with
     * (a confirmation always names it; a question may leave it out).
     */
    private static Closure requireSameDigest(Closure closure, String commandDigest) {
        if (commandDigest != null && !closure.commandDigest().equals(commandDigest)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This operation id was already used for another command");
        }
        return closure;
    }

    private static List<ClosureBlocker> sorted(Collection<ClosureBlocker> blockers) {
        return blockers.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.CLOSURE_NOT_FOUND, "No such table session operation");
    }
}
