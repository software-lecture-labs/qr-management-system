package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * The kitchen's request to cancel a whole order. It is a REQUEST: creating one changes neither the kitchen order's
 * preparation status nor anything the Order service owns. What was asked (who, why, when) never changes afterwards;
 * only the Order service's decision can later be added.
 */
@Entity
@Table(name = "cancellation_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CancellationRequest implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CancellationRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, updatable = false, length = 40)
	private CancellationReason reasonCode;

	@Column(name = "reason_text", updatable = false, length = 500)
	private String reasonText;

	@Column(name = "product_id", updatable = false)
	private UUID productId;

	@Column(name = "order_item_id", updatable = false)
	private UUID orderItemId;

	@Column(name = "requested_by_user_id", nullable = false, updatable = false)
	private UUID requestedByUserId;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "decision_reason_code", length = 60)
	private String decisionReasonCode;

	@Column(name = "decision_order_version")
	private Long decisionOrderVersion;

	@Column(name = "decision_event_id")
	private UUID decisionEventId;

	@Version
	private Long version;

	@Transient
	private boolean isNew = true;

	/** Everything the client asked for; the restaurant, the user and the time come from the verified caller and the server. */
	public record NewRequest(UUID restaurantId, UUID orderId, UUID commandId, CancellationReason reasonCode, String reasonText,
			UUID productId, UUID orderItemId, UUID requestedByUserId, Instant requestedAt) {
	}

	public static CancellationRequest open(NewRequest data) {
		CancellationRequest request = new CancellationRequest();
		request.id = UUID.randomUUID();
		request.restaurantId = data.restaurantId();
		request.orderId = data.orderId();
		request.commandId = data.commandId();
		request.status = CancellationRequestStatus.REQUESTED;
		request.reasonCode = data.reasonCode();
		request.reasonText = data.reasonText();
		request.productId = data.productId();
		request.orderItemId = data.orderItemId();
		request.requestedByUserId = data.requestedByUserId();
		request.requestedAt = data.requestedAt();
		return request;
	}

	/** What the Order service decided, written exactly once (the database refuses any later change). */
	public record Decision(Instant decidedAt, String reasonCode, long orderVersion, UUID eventId) {
	}

	/** The Order service approved: this request is APPROVED. The kitchen order's own change is made by the caller. */
	public void approve(Decision decision) {
		decide(CancellationRequestStatus.APPROVED, decision);
	}

	/** The Order service refused: this request is REJECTED; the kitchen order is not touched. */
	public void reject(Decision decision) {
		decide(CancellationRequestStatus.REJECTED, decision);
	}

	private void decide(CancellationRequestStatus outcome, Decision decision) {
		if (status != CancellationRequestStatus.REQUESTED) {
			throw new IllegalStateException("A decided cancellation request is final");
		}
		status = outcome;
		decidedAt = decision.decidedAt().truncatedTo(ChronoUnit.MICROS);
		decisionReasonCode = decision.reasonCode();
		decisionOrderVersion = decision.orderVersion();
		decisionEventId = decision.eventId();
	}

	/** Is this exactly the request that is being sent again (same order, reason, text, stock reference and user)? */
	public boolean isSameRequest(UUID orderId, CancellationReason reasonCode, String reasonText, UUID productId,
			UUID orderItemId, UUID requestedByUserId) {
		return this.orderId.equals(orderId) && this.reasonCode == reasonCode && Objects.equals(this.reasonText, reasonText)
				&& Objects.equals(this.productId, productId) && Objects.equals(this.orderItemId, orderItemId)
				&& this.requestedByUserId.equals(requestedByUserId);
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}
}
