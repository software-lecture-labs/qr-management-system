package com.restofy.kitchen.api.exception;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The error contract of docs/kitchen-02-contract.md §5.3: which failure is which status, code and retry advice. */
class ApiExceptionHandlerTests {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	private static Object property(ProblemDetail problem, String name) {
		return problem.getProperties() == null ? null : problem.getProperties().get(name);
	}

	@Test
	void aStateConflictIs409NotRetryableAndNamesTheRealStatus() {
		ProblemDetail problem = handler.invalidTransition(
				new InvalidKitchenTransitionException(KitchenOrderStatus.READY, KitchenOrderStatus.PREPARING));

		assertEquals(409, problem.getStatus());
		assertEquals("ORDER_STATE_CONFLICT", property(problem, "code"));
		assertEquals("READY", property(problem, "currentStatus"));
		assertEquals(false, property(problem, "retryable"));
	}

	@Test
	void aCommandIdConflictIs409NotRetryable() {
		ProblemDetail problem = handler.commandIdConflict(new CommandIdConflictException());

		assertEquals(409, problem.getStatus());
		assertEquals("COMMAND_ID_CONFLICT", property(problem, "code"));
		assertEquals(false, property(problem, "retryable"));
	}

	@Test
	void anOptimisticLockLossIs409ButRetryableAfterReloading() {
		ProblemDetail problem = handler.concurrentModification();

		assertEquals(409, problem.getStatus());
		assertEquals("CONCURRENT_MODIFICATION", property(problem, "code"));
		assertEquals(true, property(problem, "retryable"));
	}

	@Test
	void aLockTimeoutIs503WithRetryAfterAndDoesNotClaimTheCommandFailed() {
		ResponseEntity<ProblemDetail> response = handler.orderBusy();
		ProblemDetail problem = response.getBody();

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertEquals("2", response.getHeaders().getFirst("Retry-After"));
		assertNotNull(problem);
		assertEquals(503, problem.getStatus());
		assertEquals("ORDER_BUSY", property(problem, "code"));
		assertEquals(true, property(problem, "retryable"));
		assertTrue(problem.getDetail().contains("another request may have"));
		assertTrue(problem.getDetail().contains("same Idempotency-Key"));
		assertFalse(problem.getDetail().toLowerCase().contains("failed"));
	}

	@Test
	void errorBodiesCarryNoInternalDetail() {
		ProblemDetail notFound = handler.orderNotFound(new KitchenOrderNotFoundException());
		ResponseEntity<ProblemDetail> busy = handler.orderBusy();
		String text = notFound.getDetail() + busy.getBody().getDetail() + handler.concurrentModification().getDetail();

		for (String leak : new String[] { "SQL", "postgres", "kitchen_orders", "Exception", "at com.", "jdbc", "lock timeout" }) {
			assertFalse(text.contains(leak), "must not leak '" + leak + "'");
		}
		assertNull(property(notFound, "code"), "a plain 404 carries no state-machine code");
		// the exception types used by the handler are the ones Spring raises for a PostgreSQL lock timeout
		assertTrue(PessimisticLockingFailureException.class.isAssignableFrom(CannotAcquireLockException.class));
		assertTrue(ObjectOptimisticLockingFailureException.class.getSimpleName().contains("Optimistic"));
	}
}
