package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.CommandOutcome;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
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
import java.util.Objects;
import java.util.UUID;

/**
 * Audit and idempotency record of one availability command sent to the Menu service. It does NOT hold the product's
 * availability (Menu is the only author of that): only who asked for what and what Menu answered. REJECTED and
 * CONFIRMED are final for the command; a repeat of the same command is re-asked from Menu, never answered from here.
 * The record is only inserted through this entity; attempts and outcomes change through the repository's atomic,
 * conditional updates (parallel retries of one command id must not collide).
 */
@Entity
@Table(name = "product_availability_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductAvailabilityCommand implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_status", nullable = false, updatable = false, length = 30)
	private ProductAvailabilityStatus targetStatus;

	@Column(name = "reason_code", updatable = false, length = 40)
	private String reasonCode;

	@Column(name = "reason_text", updatable = false, length = 500)
	private String reasonText;

	@Column(name = "requested_by_user_id", nullable = false, updatable = false)
	private UUID requestedByUserId;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CommandOutcome outcome;

	@Column(name = "outcome_code", length = 80)
	private String outcomeCode;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Version
	private Long version;

	@Transient
	private boolean isNew = true;

	public record Request(UUID restaurantId, UUID commandId, UUID productId, ProductAvailabilityStatus targetStatus,
			String reasonCode, String reasonText, UUID requestedByUserId, Instant requestedAt) {
	}

	public static ProductAvailabilityCommand request(Request request) {
		ProductAvailabilityCommand command = new ProductAvailabilityCommand();
		command.id = UUID.randomUUID();
		command.restaurantId = request.restaurantId();
		command.commandId = request.commandId();
		command.productId = request.productId();
		command.targetStatus = request.targetStatus();
		command.reasonCode = request.reasonCode();
		command.reasonText = request.reasonText();
		command.requestedByUserId = request.requestedByUserId();
		command.requestedAt = request.requestedAt();
		command.outcome = CommandOutcome.UNCONFIRMED;
		return command;
	}

	/** Is this exactly the command that is being sent again (same product, status, reason, text and user)? */
	public boolean isSameCommand(UUID productId, ProductAvailabilityStatus targetStatus, String reasonCode,
			String reasonText, UUID requestedByUserId) {
		return this.productId.equals(productId) && this.targetStatus == targetStatus && Objects.equals(this.reasonCode, reasonCode)
				&& Objects.equals(this.reasonText, reasonText) && this.requestedByUserId.equals(requestedByUserId);
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
