package com.restofy.qr.application.service;

import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.SessionTransition.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Automatic QR side of the durable Order-gated protocol. No staff approval or Order/Payment ledger is stored. */
@Service
public class TableSessionClosureService {
    private final SessionTransitionTransactions transitions;
    private final SessionClosureTransactions cleaning;
    private final SessionOrderProtection protection;
    private final OrderSessionAccess orders;
    private final Clock clock;
    public TableSessionClosureService(SessionTransitionTransactions transitions, SessionClosureTransactions cleaning,
                                       SessionOrderProtection protection, OrderSessionAccess orders, Clock clock) {
        this.transitions = transitions; this.cleaning = cleaning; this.protection = protection; this.orders = orders; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public OperationView evaluate(UUID restaurantId, UUID sessionId, Purpose purpose) {
        var state = protection.inspect(restaurantId, sessionId);
        UUID operationId = transitions.begin(restaurantId, sessionId, purpose, state);
        return operationId == null ? null : resumeOperation(restaurantId, operationId);
    }

    @Transactional(propagation = Propagation.NEVER)
    public OperationView resume(UUID restaurantId, UUID operationId) {
        return resumeOperation(restaurantId, operationId);
    }

    private OperationView resumeOperation(UUID restaurantId, UUID operationId) {
        var attempt = transitions.attempt(restaurantId, operationId);
        var command = attempt.command();
        if (attempt.stage() == Stage.COMPLETED || attempt.stage() == Stage.ABORTED) { return transitions.view(restaurantId, operationId); }
        if (attempt.stage() == Stage.APPLIED) {
            acknowledge(command, attempt.result());
            return transitions.view(restaurantId, operationId);
        }
        if (attempt.stage() == Stage.ABORTING) { return abort(command); }
        var authority = permission(attempt);
        if (!current(authority, command)) { return transitions.view(restaurantId, operationId); }
        if ((authority.status() == SourceStatus.BLOCKED || authority.status() == SourceStatus.UNKNOWN)
                && authority.effect() == Effect.NONE && authority.idleGuestIds().isEmpty()) {
            transitions.requireAbort(restaurantId, operationId); return abort(command);
        }
        if (!authority.permits(command, clock.instant())) { return transitions.view(restaurantId, operationId); }
        var applied = transitions.apply(restaurantId, operationId, authority);
        if (applied == ApplyOutcome.ABORT_REQUIRED) { return abort(command); }
        if (applied == ApplyOutcome.REFRESH_REQUIRED) { return transitions.view(restaurantId, operationId); }
        var view = transitions.view(restaurantId, operationId);
        acknowledge(command, view.result());
        return transitions.view(restaurantId, operationId);
    }

    private boolean current(Authority authority, Command command) {
        return authority != null && authority.isCurrent(command, clock.instant());
    }

    private Authority permission(SessionTransitionTransactions.Attempt attempt) {
        var command = attempt.command();
        var authority = attempt.previousAttempts() == 0 ? orders.prepare(command) : orders.status(command);
        if (authority != null && authority.expiredFor(command, clock.instant())) { authority = orders.status(command); }
        return current(authority, command) && authority.status() == SourceStatus.NOT_STARTED ? orders.prepare(command) : authority;
    }

    private OperationView abort(Command command) {
        var restaurantId = command.snapshot().restaurantId();
        var confirmation = orders.abort(command);
        if (confirmation != null && confirmation.confirmsAbort(command, clock.instant())) {
            transitions.abortConfirmed(restaurantId, command.operationId());
        }
        return transitions.view(restaurantId, command.operationId());
    }

    private void acknowledge(Command command, LocalResult result) {
        var confirmation = orders.acknowledge(command, result);
        if (confirmation != null && confirmation.confirms(command, result, clock.instant())) {
            transitions.acknowledge(command.snapshot().restaurantId(), command.operationId(), confirmation);
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public Result cleaned(UUID sessionId, long expectedTableVersion) { return cleaning.cleaned(sessionId, expectedTableVersion); }
}
