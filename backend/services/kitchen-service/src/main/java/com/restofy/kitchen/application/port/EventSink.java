package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.domain.enums.DeliveryOutcome;

/**
 * Boundary towards ONE other service that receives Kitchen's outbox events. A sink is bound to one destination, and the
 * delivery of an event to that destination is tracked on its own: another destination being down, slow or final changes
 * nothing here. There is NO production implementation of any sink: the transport (D-10), the target address and the
 * service-to-service identity are not decided, and none is invented. An adapter added later must honour this contract,
 * which the delivery tests pin down:
 * <ul>
 *   <li>Return {@link DeliveryOutcome#DELIVERED} only for a positive acknowledgement of THIS event id.</li>
 *   <li>A timeout, connection problem, 5xx, throttling or any unknown result is {@code RETRYABLE_FAILURE} (throwing
 *       a runtime exception counts as the same): the outcome is unknown, so the same event id is sent again.</li>
 *   <li>Only a definite, non-repairable rejection (e.g. the target says the contract is unsupported) is
 *       {@code PERMANENT_FAILURE}.</li>
 *   <li>The receiver must treat the event id idempotently: delivery is at-least-once, never exactly-once.</li>
 *   <li>The adapter does not retry on its own and holds no database lock while it works.</li>
 * </ul>
 */
public interface EventSink {

	Result deliver(OutboundMessage message);

	/** The destination this sink serves; the delivery service never hands it a delivery meant for another one. */
	String destination();

	/** Result of one attempt; {@code errorCode} is a short, safe code (never a message, address or token). */
	record Result(DeliveryOutcome outcome, String errorCode) {

		public static Result delivered() {
			return new Result(DeliveryOutcome.DELIVERED, null);
		}

		public static Result retry(String errorCode) {
			return new Result(DeliveryOutcome.RETRYABLE_FAILURE, errorCode);
		}

		public static Result permanentFailure(String errorCode) {
			return new Result(DeliveryOutcome.PERMANENT_FAILURE, errorCode);
		}
	}
}
