package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.contract.ProductAvailabilityState;

/**
 * Boundary towards the Menu service, the single author of product availability. There is NO production adapter: the
 * Menu service has no availability endpoint yet, so nothing is invented. Until one is wired, the default bean answers
 * {@link Kind#UNAVAILABLE} ("MENU_NOT_INTEGRATED") and Kitchen never reports a product as closed.
 *
 * <p>Contract an adapter must honour: {@code APPLIED} only for Menu's positive confirmation of THIS command id
 * (repeating the same command id returns the original confirmation); any timeout, connection problem, 5xx or unknown
 * answer is {@code UNAVAILABLE} (outcome unknown, never success); the adapter does not retry on its own and holds
 * no database lock while it works.
 */
public interface MenuAvailabilityPort {

	Result change(MenuAvailabilityCommand command);

	enum Kind {
		/** Menu confirmed and recorded the change. */
		APPLIED,
		/** Unknown product, or a product of another restaurant. */
		PRODUCT_NOT_FOUND,
		/** The product exists but is passive in the catalogue, so its availability cannot be changed. */
		PRODUCT_INACTIVE,
		/** Menu rejected the command content. */
		INVALID_COMMAND,
		/** The same command id was already used by Menu for a different command. */
		COMMAND_CONFLICT,
		/** A conditional (version) update lost against a newer Menu state. */
		VERSION_CONFLICT,
		/** Menu cannot be reached or did not answer reliably: the outcome is UNKNOWN. */
		UNAVAILABLE
	}

	/** {@code errorCode} is a short safe code, never a message, address or token. */
	record Result(Kind kind, ProductAvailabilityState state, String errorCode) {

		public static Result applied(ProductAvailabilityState state) {
			return new Result(Kind.APPLIED, state, null);
		}

		public static Result rejected(Kind kind, String errorCode) {
			return new Result(kind, null, errorCode);
		}

		public static Result unavailable(String errorCode) {
			return new Result(Kind.UNAVAILABLE, null, errorCode);
		}
	}
}
