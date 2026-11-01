package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.support.FakeOrderDecisionSource;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.rejected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proposed decision contract: what its content alone must satisfy before Kitchen even looks at an order. */
class OrderCancellationDecisionMessageTests {

	private final UUID restaurant = UUID.randomUUID();
	private final UUID order = UUID.randomUUID();
	private final UUID request = UUID.randomUUID();

	private FakeOrderDecisionSource.Draft ok() {
		return approved(restaurant, order, request, 5);
	}

	@Test
	void anApprovalAndARefusalBuiltFromTheContractAreWellFormed() {
		assertNull(ok().message().shapeProblem());
		assertNull(rejected(restaurant, order, request, 5).message().shapeProblem());
		assertEquals("OrderCancelled", ok().message().eventType());
		assertEquals("OrderCancellationRejected", rejected(restaurant, order, request, 5).message().eventType());
		assertEquals(1, ok().message().schemaVersion());
	}

	@Test
	void anyMissingMandatoryFieldIsRefused() {
		assertEquals("MISSING_FIELD", ok().eventId(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().restaurant(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().order(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().request(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().decision(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().at(null).message().shapeProblem());
		assertEquals("MISSING_FIELD", ok().type(null).message().shapeProblem());
	}

	@Test
	void onlySchemaVersionOneIsUnderstood() {
		assertEquals("UNSUPPORTED_SCHEMA", ok().schema(0).message().shapeProblem());
		assertEquals("UNSUPPORTED_SCHEMA", ok().schema(2).message().shapeProblem());
	}

	@Test
	void theEventTypeMustAgreeWithTheDecision() {
		assertEquals("TYPE_DECISION_MISMATCH", ok().type("OrderCancellationRejected").message().shapeProblem());
		assertEquals("TYPE_DECISION_MISMATCH", rejected(restaurant, order, request, 5).type("OrderCancelled").message().shapeProblem());
		assertEquals("TYPE_DECISION_MISMATCH", ok().type("Anything").message().shapeProblem());
		assertEquals("TYPE_DECISION_MISMATCH", ok().decision(OrderDecision.REJECTED).message().shapeProblem());
	}

	@Test
	void theVersionReasonCodeAndCorrelationAreBounded() {
		assertEquals("BAD_VERSION", ok().version(-1).message().shapeProblem());
		assertNull(ok().version(0).message().shapeProblem());
		for (String bad : new String[] { "lower", "A", "HAS SPACE", "WITH-DASH", "1START", "A".repeat(61), "ÇOK", "X\n" }) {
			assertEquals("BAD_REASON_CODE", ok().reason(bad).message().shapeProblem(), bad);
		}
		assertNull(ok().reason("A".repeat(60)).message().shapeProblem());
		assertNull(ok().reason(null).message().shapeProblem());
		assertEquals("BAD_CORRELATION_ID", ok().correlation("x".repeat(65)).message().shapeProblem());
		assertEquals("BAD_CORRELATION_ID", ok().correlation("a\nb").message().shapeProblem());
		assertNull(ok().correlation(null).message().shapeProblem());
	}

	@Test
	void onlyTheFirstFiveOutcomesAreRecordedInTheInbox() {
		for (DecisionOutcome outcome : new DecisionOutcome[] { DecisionOutcome.CANCELLED, DecisionOutcome.REQUEST_REJECTED, DecisionOutcome.STALE,
				DecisionOutcome.ALREADY_DECIDED, DecisionOutcome.NEEDS_RECONCILIATION }) {
			assertTrue(outcome.isRecorded(), outcome.name());
		}
		for (DecisionOutcome outcome : new DecisionOutcome[] { DecisionOutcome.UNKNOWN, DecisionOutcome.INVALID,
				DecisionOutcome.SOURCE_NOT_TRUSTED }) {
			assertFalse(outcome.isRecorded(), outcome.name());
		}
	}
}
