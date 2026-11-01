package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.OrderDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proposed, versioned decision of the Order service on a cancellation request (schemaVersion 1). NOT agreed with or
 * implemented by the Order service, and there is no production transport for it: nothing receives it in production
 * (D-09 service identity and D-10 transport are open). It reaches Kitchen only through
 * {@code OrderDecisionService#apply}, which a future AUTHENTICATED adapter must call with the verified sender; the body
 * never says who sent it.
 *
 * <p>{@code eventType} is {@value #TYPE_APPROVED} only for an approval and {@value #TYPE_REJECTED} only for a refusal,
 * and must agree with {@code decision}. {@code orderVersion} is the Order service's own version of the order after the
 * decision. {@code requestId} is the id Kitchen gave the request (the event Kitchen sent carried it). {@code reasonCode}
 * is the Order service's own short code (e.g. why it refused); Kitchen never shows it as free text.
 */
public record OrderCancellationDecisionMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		UUID restaurantId,
		UUID orderId,
		UUID requestId,
		long orderVersion,
		OrderDecision decision,
		Instant decidedAt,
		String reasonCode,
		String correlationId) {

	public static final String TYPE_APPROVED = "OrderCancelled";
	public static final String TYPE_REJECTED = "OrderCancellationRejected";
	public static final int SCHEMA_VERSION = 1;
	/** Decisions may be at most this far ahead of Kitchen's clock (clock skew); anything later is implausible. */
	public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

	private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
	private static final int MAX_CORRELATION_ID = 64;

	/**
	 * Why this message cannot be trusted by its own content alone (no database needed), or {@code null} if it is fine.
	 * The answer is a short code, never an echo of what was sent.
	 */
	public String shapeProblem() {
		if (eventId == null || restaurantId == null || orderId == null || requestId == null || decision == null
				|| decidedAt == null || eventType == null) {
			return "MISSING_FIELD";
		}
		if (schemaVersion != SCHEMA_VERSION) {
			return "UNSUPPORTED_SCHEMA";
		}
		boolean approved = decision == OrderDecision.APPROVED;
		if (!(approved ? TYPE_APPROVED : TYPE_REJECTED).equals(eventType)) {
			return "TYPE_DECISION_MISMATCH";
		}
		if (orderVersion < 0) {
			return "BAD_VERSION";
		}
		if (reasonCode != null && !REASON_CODE.matcher(reasonCode).matches()) {
			return "BAD_REASON_CODE";
		}
		if (correlationId != null && (correlationId.length() > MAX_CORRELATION_ID || correlationId.chars().anyMatch(Character::isISOControl))) {
			return "BAD_CORRELATION_ID";
		}
		return null;
	}
}
