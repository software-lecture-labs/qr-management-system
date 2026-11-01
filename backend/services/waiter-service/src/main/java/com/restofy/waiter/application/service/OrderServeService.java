package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.OrderServeCommand;
import com.restofy.waiter.domain.enums.ServeCommandStatus;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.domain.exception.ServeRejectedException;
import com.restofy.waiter.domain.exception.ServeResultUnknownException;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderRejectedException;
import com.restofy.waiter.infrastructure.client.order.ServeCommandRequestDto;
import com.restofy.waiter.infrastructure.client.order.ServeResponseDto;
import com.restofy.waiter.infrastructure.client.order.ServeStatusDto;
import com.restofy.waiter.infrastructure.config.RestClientProperties;
import com.restofy.waiter.infrastructure.persistence.repository.OrderServeCommandRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Forwards "this order was served" to Order and reconciles the outcome. <b>Order is the only authority of the
 * served fact.</b> Waiter does not pre-check readiness, cancellation or version with a read (a check followed by
 * a write proves nothing); it sends one idempotent command that carries the context the staff member saw, and
 * Order decides atomically. Waiter keeps nothing but a technical record of the command.
 *
 * <p>The remote call is not part of any local transaction, so its outcome can be unknown: a timeout or a lost
 * answer may hide a command that Order did apply. Waiter then reports neither "served" nor "failed". The
 * command id is fixed for the life of the command and is the idempotency key towards Order, which makes two
 * ways of finding out safe: asking Order what it recorded for that id, and repeating the same request with the
 * same Idempotency-Key. Nothing is ever sent again on its own.
 */
@Service
public class OrderServeService {

    /** Rejection recorded when Order refused to even look at the request (no service identity yet). */
    public static final String DEPENDENCY_REJECTED = "DEPENDENCY_REJECTED";
    /** {@code reconciliation} value: Order was asked and has no record of the command. */
    public static final String NOT_RECORDED = "NOT_RECORDED";

    /** A command and, when Order answered in this call, Order's view of the order (otherwise null). */
    public record Result(OrderServeCommand command, OrderDto order, String reconciliation) {
    }

    private final OrderClient orders;
    private final OrderServeCommandRepository commands;
    private final RestClientProperties timeouts;
    private final Clock clock;

    public OrderServeService(OrderClient orders, OrderServeCommandRepository commands, RestClientProperties timeouts,
                             Clock clock) {
        this.orders = orders;
        this.commands = commands;
        this.timeouts = timeouts;
        this.clock = clock;
    }

    /**
     * @throws ServeRejectedException      Order (or its trust boundary) definitively did not apply the command
     * @throws ServeResultUnknownException the command may or may not have been applied
     * @throws CallConflictException       same key with another request, or an attempt is already in flight
     */
    public Result serve(AuthenticatedStaff staff, UUID orderId, UUID tableSessionId, long expectedVersion,
                        String idempotencyKey) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        String key = IdempotencyKeys.require(idempotencyKey);
        String digest = IdempotencyKeys.digest("SERVE", orderId, tableSessionId, expectedVersion);
        OrderServeCommand command;
        try {
            command = commands.saveAndFlush(OrderServeCommand.open(UUID.randomUUID(), restaurantId, orderId,
                    tableSessionId, expectedVersion, staff.staffUserId(), key, digest, now()));
        } catch (DataIntegrityViolationException sameKey) {
            OrderServeCommand existing = commands.findByRestaurantIdAndRequestedByUserIdAndIdempotencyKey(
                    restaurantId, staff.staffUserId(), key).orElseThrow(() -> sameKey);
            return replay(existing, digest);
        }
        return send(command);
    }

    /**
     * The state of a command. Answered commands are returned as recorded. For an unanswered one Order is asked
     * what it recorded for the command id; this read never resends the command.
     */
    public Result status(AuthenticatedStaff staff, UUID orderId, UUID commandId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        OrderServeCommand command = commands.findByIdAndRestaurantIdAndOrderId(commandId, restaurantId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("SERVE_COMMAND_NOT_FOUND", "Serve command not found"));
        if (!command.isUnanswered() || isInFlight(command)) return new Result(command, null, null);
        Optional<ServeStatusDto> recorded = orders.serveStatus(restaurantId, orderId, commandId);
        try {
            if (recorded.isPresent()) return settle(command, recorded.get());
            if (command.getStatus() == ServeCommandStatus.PENDING) {
                command.markUnknown(now());
                commands.saveAndFlush(command);
            }
            return new Result(command, null, NOT_RECORDED);
        } catch (ObjectOptimisticLockingFailureException concurrentUpdate) {
            return new Result(commands.findById(commandId).orElse(command), null, null);
        }
    }

    // ----- internals -----

    /**
     * A repeat of a known command. Same key and same request: an answered command repeats its answer without
     * touching Order again; an unanswered one is reconciled. Same key with another request is a conflict.
     */
    private Result replay(OrderServeCommand existing, String digest) {
        if (!existing.getRequestDigest().equals(digest)) {
            throw new CallConflictException("IDEMPOTENCY_KEY_REUSED",
                    "The Idempotency-Key was already used for a different request");
        }
        return switch (existing.getStatus()) {
            case CONFIRMED -> new Result(existing, currentOrderOrNull(existing), null);
            case REJECTED -> throw rejection(existing);
            case PENDING, UNKNOWN -> resume(existing);
        };
    }

    /**
     * Continues a command whose answer is missing: ask Order what it recorded; only if Order has no record,
     * send the same command again under the same id (Order must deduplicate by id, see the contract).
     */
    private Result resume(OrderServeCommand loaded) {
        if (isInFlight(loaded)) throw inProgress();
        // Saving returns the current (managed) instance with the new version; the loaded one is stale from here on.
        OrderServeCommand command;
        try {
            loaded.markInFlight(now());
            command = commands.saveAndFlush(loaded);
        } catch (ObjectOptimisticLockingFailureException concurrentAttempt) {
            throw inProgress();
        }
        Optional<ServeStatusDto> recorded;
        try {
            recorded = orders.serveStatus(command.getRestaurantId(), command.getOrderId(), command.getId());
        } catch (DependencyException failure) {
            return unknown(command, failure.failure());
        }
        if (recorded.isEmpty()) return send(command);
        Result settled = settle(command, recorded.get());
        if (settled.command().getStatus() == ServeCommandStatus.REJECTED) throw rejection(settled.command());
        return settled;
    }

    private Result send(OrderServeCommand command) {
        ServeCommandRequestDto request = new ServeCommandRequestDto(command.getId(), command.getRestaurantId(),
                command.getTableSessionId(), command.getExpectedVersion(), command.getRequestedByUserId(),
                command.getCreatedAt());
        ServeResponseDto answer;
        try {
            answer = orders.serve(command.getOrderId(), request);
        } catch (OrderRejectedException refusal) {
            command.reject(refusal.code(), now());
            commands.saveAndFlush(command);
            throw rejection(command);
        } catch (DependencyException failure) {
            if (failure.failure() == DependencyFailure.REJECTED) {
                command.reject(DEPENDENCY_REJECTED, now());
                commands.saveAndFlush(command);
                throw rejection(command);
            }
            return unknown(command, failure.failure());
        }
        command.confirm(now());
        commands.saveAndFlush(command);
        return new Result(command, answer.order(), null);
    }

    private Result settle(OrderServeCommand command, ServeStatusDto recorded) {
        if (recorded.status() == ServeStatusDto.Status.APPLIED) {
            command.confirm(now());
            commands.saveAndFlush(command);
            return new Result(command, recorded.order(), null);
        }
        command.reject(OrderClient.knownRejection(recorded.rejectionCode()), now());
        commands.saveAndFlush(command);
        return new Result(command, null, null);
    }

    /** No usable answer: remember that the outcome is unknown and tell the caller so, never "served" or "failed". */
    private Result unknown(OrderServeCommand command, DependencyFailure failure) {
        command.markUnknown(now());
        commands.saveAndFlush(command);
        throw new ServeResultUnknownException(command.getId(), failure);
    }

    private OrderDto currentOrderOrNull(OrderServeCommand command) {
        try {
            return orders.findOrder(command.getRestaurantId(), command.getOrderId()).orElse(null);
        } catch (DependencyException unavailable) {
            return null;   // The command is confirmed whether or not the order can be read right now.
        }
    }

    private boolean isInFlight(OrderServeCommand command) {
        return command.getStatus() == ServeCommandStatus.PENDING
                && command.getUpdatedAt().isAfter(now().minus(timeouts.inFlightWindow()));
    }

    static ServeRejectedException rejection(OrderServeCommand command) {
        String code = command.getRejectionCode();
        int status = switch (code) {
            case OrderRejectedException.NOT_FOUND -> HttpStatus.NOT_FOUND.value();
            case DEPENDENCY_REJECTED -> HttpStatus.BAD_GATEWAY.value();
            default -> HttpStatus.CONFLICT.value();
        };
        String message = switch (code) {
            case OrderRejectedException.NOT_FOUND -> "Order not found";
            case DEPENDENCY_REJECTED -> "The order service did not accept the request from the waiter service";
            default -> "The order service did not apply the serve command";
        };
        return new ServeRejectedException(status, code, command.getId(), message);
    }

    private static CallConflictException inProgress() {
        return new CallConflictException("SERVE_IN_PROGRESS",
                "An attempt for this command is still running; ask for its status before repeating it");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
