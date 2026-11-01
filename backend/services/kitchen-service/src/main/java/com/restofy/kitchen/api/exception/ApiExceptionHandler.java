package com.restofy.kitchen.api.exception;

import com.restofy.kitchen.application.service.ProductAvailabilityException;
import com.restofy.kitchen.domain.exception.CancellationAlreadyRequestedException;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.InvalidStockReferenceException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.infrastructure.config.MenuIntegrationConfiguration;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders standard MVC failures (bad parameter, type mismatch, validation, unsupported method) as RFC 9457
 * ProblemDetail without stack traces or internals. Authentication/authorization failures (401/403) are produced
 * by the security filter chain before reaching this advice.
 *
 * <p>Conflicts the client must resolve are 409 with a machine-readable {@code code}; a transient lock timeout is a
 * different thing (503 + Retry-After + {@code code=ORDER_BUSY}), so clients can retry the one and not the other.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	public static final String CODE = "code";
	public static final String RETRYABLE = "retryable";
	public static final String ORDER_STATE_CONFLICT = "ORDER_STATE_CONFLICT";
	public static final String COMMAND_ID_CONFLICT = "COMMAND_ID_CONFLICT";
	public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
	public static final String ORDER_BUSY = "ORDER_BUSY";
	public static final String CANCELLATION_ALREADY_REQUESTED = "CANCELLATION_ALREADY_REQUESTED";
	public static final String INVALID_STOCK_REFERENCE = "INVALID_STOCK_REFERENCE";
	public static final String AVAILABILITY_CONFLICT = "AVAILABILITY_CONFLICT";
	public static final String MENU_UNAVAILABLE = "MENU_UNAVAILABLE";

	/** Seconds a client should wait before retrying a busy order. */
	static final String RETRY_AFTER_SECONDS = "2";

	@ExceptionHandler(KitchenOrderNotFoundException.class)
	ProblemDetail orderNotFound(KitchenOrderNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** A command that the order's current status does not allow. Nothing was changed. Retrying will not help. */
	@ExceptionHandler(InvalidKitchenTransitionException.class)
	ProblemDetail invalidTransition(InvalidKitchenTransitionException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty(CODE, ORDER_STATE_CONFLICT);
		problem.setProperty("currentStatus", e.getCurrentStatus().name());
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** The Idempotency-Key belongs to a different command (other order, other actor or other transition). */
	@ExceptionHandler(CommandIdConflictException.class)
	ProblemDetail commandIdConflict(CommandIdConflictException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty(CODE, COMMAND_ID_CONFLICT);
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** The stock reference of a cancellation request does not fit the reason or this order. Nothing was stored. */
	@ExceptionHandler(InvalidStockReferenceException.class)
	ProblemDetail invalidStockReference(InvalidStockReferenceException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setProperty(CODE, INVALID_STOCK_REFERENCE);
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** The order's cancellation request is still waiting for the Order service: a second request is refused. */
	@ExceptionHandler(CancellationAlreadyRequestedException.class)
	ProblemDetail cancellationAlreadyRequested(CancellationAlreadyRequestedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty(CODE, CANCELLATION_ALREADY_REQUESTED);
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** Lost an optimistic-lock race: the order changed under this request. Nothing was changed by it; reload first. */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail concurrentModification() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The order was changed by another request; reload it before deciding what to do");
		problem.setProperty(CODE, CONCURRENT_MODIFICATION);
		problem.setProperty(RETRYABLE, true);
		return problem;
	}

	/**
	 * An availability command that did not succeed. Nothing in Kitchen was shown as changed. 503 means the outcome at
	 * the Menu service is UNKNOWN (the change may or may not have been applied); it is never reported as a success.
	 */
	@ExceptionHandler(ProductAvailabilityException.class)
	ResponseEntity<ProblemDetail> availabilityFailed(ProductAvailabilityException e) {
		switch (e.getKind()) {
			case PRODUCT_NOT_FOUND -> {
				return problem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", false, "Product not found");
			}
			case PRODUCT_INACTIVE -> {
				return problem(HttpStatus.CONFLICT, "PRODUCT_INACTIVE", false,
						"The product is passive in the catalogue and its availability cannot be changed");
			}
			case INVALID_COMMAND -> {
				return problem(HttpStatus.BAD_REQUEST, "INVALID_COMMAND", false, "The Menu service rejected the command");
			}
			case COMMAND_CONFLICT -> {
				return problem(HttpStatus.CONFLICT, COMMAND_ID_CONFLICT, false, "The Idempotency-Key was already used for a different command");
			}
			case VERSION_CONFLICT, APPLIED -> {
				return problem(HttpStatus.CONFLICT, AVAILABILITY_CONFLICT, true,
						"The product is in a different availability state; reload it before deciding what to do");
			}
			default -> {
				if (MenuIntegrationConfiguration.NOT_INTEGRATED.equals(e.getCode())) {
					return problem(HttpStatus.SERVICE_UNAVAILABLE, MenuIntegrationConfiguration.NOT_INTEGRATED, false,
							"The Menu service is not integrated yet; nothing was changed");
				}
				ResponseEntity<ProblemDetail> unavailable = problem(HttpStatus.SERVICE_UNAVAILABLE, MENU_UNAVAILABLE, true,
						"The outcome at the Menu service is unknown: the change may or may not have been applied. "
								+ "Retry with the same Idempotency-Key or check the product");
				return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
						.body(unavailable.getBody());
			}
		}
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, boolean retryable, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty(CODE, code);
		problem.setProperty(RETRYABLE, retryable);
		return ResponseEntity.status(status).body(problem);
	}

	/**
	 * Timed out waiting for the order's row lock. This request changed nothing, but that does not mean the command
	 * failed: another request (possibly the same command) may have succeeded. The same Idempotency-Key can be sent
	 * again safely, or the order reloaded.
	 */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> orderBusy() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The order is busy; this request changed nothing, but another request may have. "
						+ "Retry with the same Idempotency-Key or reload the order");
		problem.setProperty(CODE, ORDER_BUSY);
		problem.setProperty(RETRYABLE, true);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
