package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.ChangeProductAvailabilityRequest;
import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.contract.ProductAvailabilityState;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.domain.entity.ProductAvailabilityCommand;
import com.restofy.kitchen.domain.enums.CommandOutcome;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.infrastructure.persistence.repository.ProductAvailabilityCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Asks the Menu service (the only author of product availability) to change a product's availability and reports
 * exactly what Menu confirmed. Kitchen keeps no availability of its own. Flow: short transaction to record the
 * command -> the Menu call with NO transaction and NO lock open -> short transaction to record the answer. A failure
 * is thrown only AFTER the answer was committed, so a rejection or an unknown outcome is always on record.
 *
 * <p>A repeat (same command id) is never answered from here: it is asked from Menu again with the same id, because
 * only Menu knows the product's state now. A command id used for a different intent is a conflict. An unknown
 * outcome (Menu unreachable, not integrated, unreliable answer) is never reported as success.
 */
@Service
public class ProductAvailabilityService {

	private static final Logger log = LoggerFactory.getLogger(ProductAvailabilityService.class);
	private static final String COMMAND_INDEX = "uq_product_availability_commands_command";
	static final String STATE_MISMATCH = "STATE_MISMATCH";
	static final String INCONSISTENT_RESPONSE = "INCONSISTENT_RESPONSE";
	static final String PORT_EXCEPTION = "PORT_EXCEPTION";

	private final ProductAvailabilityCommandRepository commands;
	private final MenuAvailabilityPort menu;
	private final TransactionTemplate tx;
	private final Clock clock;

	public ProductAvailabilityService(ProductAvailabilityCommandRepository commands, MenuAvailabilityPort menu,
			TransactionTemplate tx, Clock clock) {
		this.commands = commands;
		this.menu = menu;
		this.tx = tx;
		this.clock = clock;
	}

	/**
	 * The committed answer of one attempt: either the state Menu confirmed or the reason it was not confirmed.
	 * {@code definitive} marks a failure that settles the command for good (a rejection by Menu).
	 */
	private record Settlement(ProductAvailabilityState state, ProductAvailabilityException failure, boolean definitive) {
	}

	public ProductAvailabilityState change(UUID restaurantId, UUID actorUserId, UUID commandId, UUID productId,
			ChangeProductAvailabilityRequest request) {
		MenuAvailabilityCommand command = new MenuAvailabilityCommand(commandId, restaurantId, productId, request.status(),
				request.reasonCode(), request.reasonText(), actorUserId);

		UUID recordId = registerOrLoad(command);
		MenuAvailabilityPort.Result result = callMenu(command);
		Settlement settlement = tx.execute(status -> settle(recordId, command, result));
		if (settlement.failure() != null) {
			throw settlement.failure();
		}
		return settlement.state();
	}

	/**
	 * Records (or finds) the command and counts this attempt. A parallel request with the same command id may win the
	 * insert; the loser's transaction is aborted by PostgreSQL, so it runs once more and then finds the winner's row.
	 */
	private UUID registerOrLoad(MenuAvailabilityCommand command) {
		try {
			return tx.execute(status -> register(command));
		} catch (DataIntegrityViolationException e) {
			if (!isCommandIndex(e)) {
				throw e;
			}
			return tx.execute(status -> register(command));
		}
	}

	private UUID register(MenuAvailabilityCommand command) {
		ProductAvailabilityCommand commandRecord = commands.findByRestaurantIdAndCommandId(command.restaurantId(), command.commandId())
				.orElse(null);
		if (commandRecord == null) {
			ProductAvailabilityCommand.Request newCommand = new ProductAvailabilityCommand.Request(command.restaurantId(),
					command.commandId(), command.productId(), command.targetStatus(), command.reasonCode(), command.reasonText(),
					command.requestedByUserId(), clock.instant());
			commandRecord = commands.saveAndFlush(ProductAvailabilityCommand.request(newCommand));
		} else {
			checkSameIntent(commandRecord, command);
		}
		commands.recordAttempt(commandRecord.getId(), clock.instant());
		return commandRecord.getId();
	}

	private ProductAvailabilityCommand checkSameIntent(ProductAvailabilityCommand commandRecord, MenuAvailabilityCommand command) {
		if (!commandRecord.isSameCommand(command.productId(), command.targetStatus(), command.reasonCode(), command.reasonText(),
				command.requestedByUserId())) {
			throw new CommandIdConflictException();
		}
		return commandRecord;
	}

	/** An exception, a missing or an inconsistent answer is an unknown outcome, never a success. */
	private MenuAvailabilityPort.Result callMenu(MenuAvailabilityCommand command) {
		try {
			MenuAvailabilityPort.Result result = menu.change(command);
			return result == null || result.kind() == null ? MenuAvailabilityPort.Result.unavailable("NO_RESULT") : result;
		} catch (RuntimeException _) {
			return MenuAvailabilityPort.Result.unavailable(PORT_EXCEPTION);
		}
	}

	/**
	 * Writes what Menu answered with conditional updates (a final outcome is never overwritten), then reports the stored
	 * decision if the command was rejected - by this or by a parallel attempt - otherwise this attempt's own answer.
	 */
	private Settlement settle(UUID recordId, MenuAvailabilityCommand command, MenuAvailabilityPort.Result result) {
		Settlement answer = judge(command, result);
		if (answer.state() != null) {
			commands.confirm(recordId, answer.state().changedAt());
		} else if (answer.definitive()) {
			commands.reject(recordId, answer.failure().getCode());
		} else {
			commands.markUnconfirmed(recordId, answer.failure().getCode());
		}
		ProductAvailabilityCommand stored = commands.findById(recordId).orElseThrow();
		if (stored.getOutcome() == CommandOutcome.REJECTED) {
			return failure(rejectedKind(stored.getOutcomeCode()), stored.getOutcomeCode(), true);
		}
		return answer;
	}

	/** What Menu's answer means for this command. An inconsistent or mismatching confirmation is never a success. */
	private static Settlement judge(MenuAvailabilityCommand command, MenuAvailabilityPort.Result result) {
		switch (result.kind()) {
			case APPLIED -> {
				ProductAvailabilityState state = result.state();
				if (state == null || !command.productId().equals(state.productId()) || state.changedAt() == null) {
					log.warn("Menu answer inconsistent: restaurantId={} productId={}", command.restaurantId(), command.productId());
					return failure(MenuAvailabilityPort.Kind.UNAVAILABLE, INCONSISTENT_RESPONSE, false);
				}
				if (state.status() != command.targetStatus()) {
					// Menu says the product is in another state than this command produced (e.g. changed since).
					return failure(MenuAvailabilityPort.Kind.VERSION_CONFLICT, STATE_MISMATCH, false);
				}
				return new Settlement(state, null, false);
			}
			case UNAVAILABLE -> {
				return failure(MenuAvailabilityPort.Kind.UNAVAILABLE, safe(result.errorCode()), false);
			}
			default -> {
				return failure(result.kind(), result.kind().name(), true);
			}
		}
	}

	private static Settlement failure(MenuAvailabilityPort.Kind kind, String code, boolean definitive) {
		return new Settlement(null, new ProductAvailabilityException(kind, code), definitive);
	}

	private static MenuAvailabilityPort.Kind rejectedKind(String storedCode) {
		try {
			return MenuAvailabilityPort.Kind.valueOf(storedCode);
		} catch (RuntimeException _) {
			return MenuAvailabilityPort.Kind.INVALID_COMMAND;
		}
	}

	private static boolean isCommandIndex(DataIntegrityViolationException e) {
		return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains(COMMAND_INDEX);
	}

	/** Only a short, harmless code is kept or shown: never a message, address or token. */
	static String safe(String code) {
		if (code == null || code.isBlank()) {
			return "UNKNOWN";
		}
		String cleaned = code.replaceAll("[^A-Za-z0-9_.-]", "_");
		return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
	}
}
