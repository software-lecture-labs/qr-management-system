package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.domain.exception.TableCommandRejectedException;
import com.restofy.waiter.domain.exception.TableCommandUnknownException;
import com.restofy.waiter.infrastructure.client.qr.QrCleanRequestDto;
import com.restofy.waiter.infrastructure.client.qr.QrCleanResultDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrCloseRequestDto;
import com.restofy.waiter.infrastructure.client.qr.QrCloseResultDto;
import com.restofy.waiter.infrastructure.client.qr.QrRejectedException;
import com.restofy.waiter.infrastructure.config.RestClientProperties;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Forwards "close this main table session" and "this table is cleaned" to QR and reconciles the outcome. <b>QR
 * is the only authority of tables, sessions, closure and cleaning.</b> Waiter never decides that a table may be
 * closed: a read of the orders followed by a write proves nothing, because a new order can be accepted between
 * the two. It sends one idempotent command that names the session and the QR version the staff member saw, and
 * QR decides atomically (stops new order acceptance, settles the orders in flight, verifies delivery, balance and
 * payment certainty with Order and Payment, ends the main session and every guest session). Nothing a client
 * says about payment or balance is an input here; the manual close has no override and no force variant.
 *
 * <p>A close is a success only when QR says that the main session and all its guest sessions ended. "Closing"
 * is a state of its own and never a success. Only then are the unresolved calls of that restaurant and that
 * session ended, in one local transaction together with the command's completion.
 *
 * <p>The remote call is not part of any local transaction, so its outcome can be unknown: a timeout or a lost
 * answer may hide a command that QR did apply. Waiter then reports neither "closed" nor "failed". The command
 * id is fixed for the life of the command and is the idempotency key towards QR, which makes two ways of finding
 * out safe: asking QR what it recorded for that id, and repeating the same request with the same
 * Idempotency-Key. Nothing is ever sent again on its own, and a close names its session id, never a table
 * number, so a late command cannot close a newer session.
 */
@Service
public class TableCommandService {

    /** Rejection recorded when QR refused to even look at the request (no service identity yet). */
    public static final String DEPENDENCY_REJECTED = "DEPENDENCY_REJECTED";
    /** {@code reconciliation} value: QR was asked and has no record of the command. */
    public static final String NOT_RECORDED = "NOT_RECORDED";
    private static final int TRANSACTION_ATTEMPTS = 3;

    /** A command and, when this call asked QR and it had no record, {@link #NOT_RECORDED} (otherwise null). */
    public record Result(TableCommand command, String reconciliation) {
    }

    /**
     * QR's answer reduced to what the command bookkeeping needs, whichever kind of command it concerns.
     * {@code reconciliation} is QR's K13 warning on a definite closure; it never changes the kind of the answer.
     */
    private record Verdict(Kind kind, Instant closedAt, int endedGuests, boolean reconciliation, long tableVersion,
                           boolean tableReady, String code, List<String> blockers) {

        enum Kind { DONE, IN_PROGRESS, REFUSED }

        static Verdict closing() {
            return new Verdict(Kind.IN_PROGRESS, null, 0, false, 0, false, null, List.of());
        }

        static Verdict closed(Instant closedAt, int endedGuests, boolean reconciliation) {
            return new Verdict(Kind.DONE, closedAt, endedGuests, reconciliation, 0, false, null, List.of());
        }

        static Verdict cleaned(long tableVersion, boolean ready) {
            return new Verdict(Kind.DONE, null, 0, false, tableVersion, ready, null, List.of());
        }

        static Verdict refused(String code, List<String> blockers) {
            return new Verdict(Kind.REFUSED, null, 0, false, 0, false, code, blockers);
        }
    }

    private final QrClient qr;
    private final TableCommandRepository commands;
    private final SessionCallCloser callCloser;
    private final TransactionTemplate transaction;
    private final RestClientProperties timeouts;
    private final Clock clock;

    public TableCommandService(QrClient qr, TableCommandRepository commands, SessionCallCloser callCloser,
                               TransactionTemplate transaction, RestClientProperties timeouts, Clock clock) {
        this.qr = qr;
        this.commands = commands;
        this.callCloser = callCloser;
        this.transaction = transaction;
        this.timeouts = timeouts;
        this.clock = clock;
    }

    /**
     * Asks QR to close the main session {@code tableSessionId} of the table. {@code reason} is the optional
     * free text of a manual close; it is audit data, not a way around any check.
     *
     * @throws TableCommandRejectedException QR (or its trust boundary) definitively did not close the session
     * @throws TableCommandUnknownException  the close may or may not have happened
     * @throws CallConflictException         same key with another request, or an attempt is already in flight
     */
    public Result close(AuthenticatedStaff staff, UUID tableId, UUID tableSessionId, long expectedSessionVersion,
                        String rawReason, String idempotencyKey) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        String key = IdempotencyKeys.require(idempotencyKey);
        String reason = CallMessage.normalize(rawReason, "reason");
        // QR limits the reason in UTF-16 units, not code points; a longer text would only be refused by QR.
        if (reason != null && reason.length() > CallMessage.MAX_LENGTH) {
            throw new InvalidRequestException("reason must be at most " + CallMessage.MAX_LENGTH + " characters");
        }
        String digest = IdempotencyKeys.digest("CLOSE", tableId, tableSessionId, expectedSessionVersion, reason);
        TableCommand.Origin origin = new TableCommand.Origin(UUID.randomUUID(), restaurantId, tableId,
                expectedSessionVersion, staff.staffUserId(), key, digest, now());
        return submit(TableCommand.close(origin, tableSessionId, reason));
    }

    /**
     * Asks QR to make a cleaned table available. A close of the table that has no final answer yet blocks the
     * command before QR is asked (QR refuses it as well while a main session is live).
     */
    public Result clean(AuthenticatedStaff staff, UUID tableId, long expectedTableVersion, String idempotencyKey) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        String key = IdempotencyKeys.require(idempotencyKey);
        String digest = IdempotencyKeys.digest("CLEAN", tableId, expectedTableVersion);
        if (commands.existsByRestaurantIdAndTableIdAndTypeAndStatusIn(restaurantId, tableId, TableCommandType.CLOSE,
                TableCommandStatus.OPEN)) {
            throw new CallConflictException("CLOSE_NOT_COMPLETED",
                    "A close of this table has no final answer yet; query it before cleaning the table");
        }
        TableCommand.Origin origin = new TableCommand.Origin(UUID.randomUUID(), restaurantId, tableId,
                expectedTableVersion, staff.staffUserId(), key, digest, now());
        return submit(TableCommand.clean(origin));
    }

    /**
     * The state of a command. Final commands are returned as recorded. For an open one QR is asked what it
     * recorded for the command id; this read never resends the command.
     */
    public Result status(AuthenticatedStaff staff, UUID tableId, UUID commandId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        TableCommand command = commands.findByIdAndRestaurantIdAndTableId(commandId, restaurantId, tableId)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_COMMAND_NOT_FOUND", "Table command not found"));
        if (!command.isOpen() || isInFlight(command)) return new Result(command, null);
        return reconcile(command);
    }

    /**
     * Asks QR what it recorded for an open command and settles the command accordingly. Used by the status
     * read and by the background reconciler; a dependency failure is not hidden.
     */
    public Result reconcile(TableCommand command) {
        Optional<Verdict> recorded = recorded(command);
        try {
            if (recorded.isPresent()) return apply(command, recorded.get());
            if (command.getStatus() == TableCommandStatus.PENDING) command.markUnknown(now());
            else command.touch(now());
            return new Result(commands.saveAndFlush(command), NOT_RECORDED);
        } catch (ObjectOptimisticLockingFailureException concurrentUpdate) {
            return new Result(commands.findById(command.getId()).orElse(command), null);
        }
    }

    /** Postpones the next automatic check of a command whose last check failed. */
    public void deferNextCheck(UUID commandId) {
        try {
            commands.findById(commandId).filter(TableCommand::isOpen).ifPresent(command -> {
                command.touch(now());
                commands.saveAndFlush(command);
            });
        } catch (ObjectOptimisticLockingFailureException concurrentUpdate) {
            // Someone else just settled or touched it; that is as good as a deferral.
        }
    }

    /**
     * Ends unresolved calls that were written into a session QR had already confirmed closed (a call is created
     * from a guest check that can be older than the close). Returns the number of calls ended.
     */
    public int endLateCalls(int limit) {
        int ended = 0;
        for (TableCommand closed : commands.findClosedSessionsWithUnresolvedCalls(
                PageRequest.of(0, limit))) {
            try {
                Integer count = transaction.execute(status -> callCloser.closeCallsOf(closed, now()));
                ended += count == null ? 0 : count;
            } catch (ObjectOptimisticLockingFailureException concurrentChange) {
                // A staff member changed a call at the same moment; the next round picks it up again.
            }
        }
        return ended;
    }

    // ----- internals -----

    private Result submit(TableCommand fresh) {
        TableCommand command;
        try {
            command = commands.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException sameKeyOrOpenCommand) {
            Optional<TableCommand> existing = commands.findByRestaurantIdAndRequestedByUserIdAndIdempotencyKey(
                    fresh.getRestaurantId(), fresh.getRequestedByUserId(), fresh.getIdempotencyKey());
            if (existing.isPresent()) return replay(existing.get(), fresh.getRequestDigest());
            throw new CallConflictException(
                    fresh.getType() == TableCommandType.CLOSE ? "CLOSE_ALREADY_OPEN" : "CLEAN_ALREADY_OPEN",
                    "Another " + fresh.getType().name().toLowerCase(Locale.ROOT)
                            + " command for this target has no final answer yet; query it instead of sending another");
        }
        return send(command, true);
    }

    /**
     * A repeat of a known command. Same key and same request: a final command repeats its answer without
     * touching QR again; an open one is reconciled. Same key with another request is a conflict.
     */
    private Result replay(TableCommand existing, String digest) {
        if (!existing.getRequestDigest().equals(digest)) {
            throw new CallConflictException("IDEMPOTENCY_KEY_REUSED",
                    "The Idempotency-Key was already used for a different request");
        }
        return switch (existing.getStatus()) {
            case COMPLETED -> new Result(existing, null);
            case REJECTED -> throw rejection(existing);
            case PENDING, IN_PROGRESS, UNKNOWN -> resume(existing);
        };
    }

    /**
     * Continues a command without a final answer: ask QR what it recorded; only if QR has no record, send the
     * same command again under the same id (QR must deduplicate by id, see the contract).
     */
    private Result resume(TableCommand loaded) {
        if (isInFlight(loaded)) throw inProgress();
        // Saving returns the current (managed) instance with the new version; the loaded one is stale from here on.
        TableCommand command;
        try {
            loaded.markInFlight(now());
            command = commands.saveAndFlush(loaded);
        } catch (ObjectOptimisticLockingFailureException concurrentAttempt) {
            throw inProgress();
        }
        Optional<Verdict> recorded;
        try {
            recorded = recorded(command);
        } catch (DependencyException failure) {
            return unknown(command, failure.failure());
        }
        if (recorded.isEmpty()) return send(command, false);
        Result settled = apply(command, recorded.get());
        if (settled.command().getStatus() == TableCommandStatus.REJECTED) throw rejection(settled.command());
        return settled;
    }

    /**
     * Sends the command once. QR refusing Waiter itself (401/403) proves only that this request was not processed;
     * it is final only for the first attempt. After an earlier attempt whose outcome is unknown, that attempt may
     * still have been applied, so the command stays unknown.
     */
    private Result send(TableCommand command, boolean firstAttempt) {
        Verdict verdict;
        try {
            verdict = dispatch(command);
        } catch (QrRejectedException refusal) {
            command.reject(refusal.code(), blockerList(refusal.blockers()), now());
            commands.saveAndFlush(command);
            throw rejection(command);
        } catch (DependencyException failure) {
            if (failure.failure() == DependencyFailure.REJECTED && firstAttempt) {
                command.reject(DEPENDENCY_REJECTED, null, now());
                commands.saveAndFlush(command);
                throw rejection(command);
            }
            return unknown(command, failure.failure());
        }
        return apply(command, verdict);
    }

    /** Settles the command with what QR said. A refusal read from QR's record ends the command as rejected. */
    private Result apply(TableCommand command, Verdict verdict) {
        return switch (verdict.kind()) {
            case DONE -> finish(command, verdict);
            case IN_PROGRESS -> {
                if (command.getStatus() == TableCommandStatus.IN_PROGRESS) command.touch(now());
                else command.markInProgress(now());
                yield new Result(commands.saveAndFlush(command), null);
            }
            case REFUSED -> {
                command.reject(verdict.code(), blockerList(verdict.blockers()), now());
                yield new Result(commands.saveAndFlush(command), null);
            }
        };
    }

    /**
     * QR says the work is done. For a close, the unresolved calls of the session end in the same local
     * transaction that completes the command; a failure rolls both back and leaves the command open, so the
     * reconciler (or the next status read) completes it once the database is back.
     */
    private Result finish(TableCommand command, Verdict verdict) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> {
                    TableCommand current = commands.findById(command.getId()).orElseThrow();
                    if (!current.isOpen()) return new Result(current, null);
                    Instant now = now();
                    if (current.getType() == TableCommandType.CLOSE) {
                        int ended = callCloser.closeCallsOf(current, now);
                        current.completeClose(verdict.closedAt(), verdict.endedGuests(), ended, verdict.reconciliation(), now);
                    } else {
                        current.completeClean(verdict.tableVersion(), verdict.tableReady(), now);
                    }
                    return new Result(commands.saveAndFlush(current), null);
                });
            } catch (ObjectOptimisticLockingFailureException lost) {
                if (attempt >= TRANSACTION_ATTEMPTS) throw inProgress();
            }
        }
    }

    private Verdict dispatch(TableCommand command) {
        if (command.getType() == TableCommandType.CLOSE) {
            return verdict(qr.closeSession(new QrCloseRequestDto(command.getId(), command.getRestaurantId(),
                    command.getTableId(), command.getTableSessionId(), command.getExpectedVersion(),
                    command.getRequestedByUserId(), command.getReasonText(), command.getCreatedAt())));
        }
        return verdict(qr.markCleaned(new QrCleanRequestDto(command.getId(), command.getRestaurantId(),
                command.getTableId(), command.getExpectedVersion(), command.getRequestedByUserId(),
                command.getCreatedAt())));
    }

    private Optional<Verdict> recorded(TableCommand command) {
        if (command.getType() == TableCommandType.CLOSE) {
            return qr.closeStatus(command.getRestaurantId(), command.getTableId(), command.getTableSessionId(),
                    command.getId()).map(TableCommandService::verdict);
        }
        return qr.cleanStatus(command.getRestaurantId(), command.getTableId(), command.getId())
                .map(TableCommandService::verdict);
    }

    private static Verdict verdict(QrCloseResultDto answer) {
        return switch (answer.state()) {
            case CLOSED -> Verdict.closed(answer.closedAt(), answer.guestSessions().ended(), answer.reconciliationRequired());
            case CLOSING -> Verdict.closing();
            case REJECTED -> Verdict.refused(QrClient.knownCloseRejection(answer.rejectionCode()),
                    QrClient.knownBlockers(answer.blockers()));
        };
    }

    private static Verdict verdict(QrCleanResultDto answer) {
        return switch (answer.status()) {
            case APPLIED, DUPLICATE -> Verdict.cleaned(answer.table().version(), answer.tableReady());
            case REJECTED -> Verdict.refused(QrClient.knownCleanRejection(answer.rejectionCode()), List.of());
        };
    }

    /** No usable answer: remember that the outcome is unknown and tell the caller so, never "closed" or "failed". */
    private Result unknown(TableCommand command, DependencyFailure failure) {
        command.markUnknown(now());
        commands.saveAndFlush(command);
        throw new TableCommandUnknownException(command.getType(), command.getId(), failure);
    }

    private boolean isInFlight(TableCommand command) {
        return command.getStatus() == TableCommandStatus.PENDING
                && command.getUpdatedAt().isAfter(now().minus(timeouts.inFlightWindow()));
    }

    private static String blockerList(List<String> blockers) {
        return blockers == null || blockers.isEmpty() ? null : String.join(",", blockers);
    }

    static TableCommandRejectedException rejection(TableCommand command) {
        String code = command.getRejectionCode();
        int status = switch (code) {
            case "SESSION_NOT_FOUND", "TABLE_NOT_FOUND" -> HttpStatus.NOT_FOUND.value();
            case DEPENDENCY_REJECTED -> HttpStatus.BAD_GATEWAY.value();
            default -> HttpStatus.CONFLICT.value();
        };
        String message = switch (code) {
            case "SESSION_NOT_FOUND", "TABLE_NOT_FOUND" -> "Table or session not found";
            case DEPENDENCY_REJECTED -> "The QR service did not accept the request from the waiter service";
            default -> "The QR service did not apply the " + command.getType().name().toLowerCase(Locale.ROOT)
                    + " command";
        };
        List<String> blockers = command.getRejectionDetail() == null ? List.of()
                : List.of(command.getRejectionDetail().split(","));
        return new TableCommandRejectedException(status, code, command.getId(), blockers, message);
    }

    private static CallConflictException inProgress() {
        return new CallConflictException("COMMAND_IN_PROGRESS",
                "An attempt for this command is still running; ask for its status before repeating it");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
