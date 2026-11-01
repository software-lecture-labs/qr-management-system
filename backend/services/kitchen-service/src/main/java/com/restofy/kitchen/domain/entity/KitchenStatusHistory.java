package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kitchen_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KitchenStatusHistory implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "kitchen_order_id", nullable = false, updatable = false)
	private UUID kitchenOrderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", updatable = false, length = 20)
	private KitchenOrderStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, updatable = false, length = 20)
	private KitchenOrderStatus toStatus;

	@Column(name = "actor_user_id", updatable = false)
	private UUID actorUserId;

	@Column(name = "source_event_id", updatable = false)
	private UUID sourceEventId;

	/** Client command identity (Idempotency-Key) of the command that caused this change; null for system entries. */
	@Column(name = "command_id", updatable = false)
	private UUID commandId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(updatable = false)
	private String reason;

	@Transient
	private boolean isNew = true;

	/** Initial entry written when an accepted order is first received (no previous status). */
	public static KitchenStatusHistory initial(KitchenOrder order, UUID sourceEventId, Instant occurredAt) {
		KitchenStatusHistory history = new KitchenStatusHistory();
		history.id = UUID.randomUUID();
		history.restaurantId = order.getRestaurantId();
		history.kitchenOrderId = order.getId();
		history.toStatus = order.getStatus();
		history.sourceEventId = sourceEventId;
		history.occurredAt = occurredAt;
		return history;
	}

	/** One applied status change, written in the same transaction as the change itself. */
	public static KitchenStatusHistory transition(KitchenOrder order, KitchenOrderStatus from, KitchenOrderStatus to,
			UUID actorUserId, UUID commandId, Instant occurredAt) {
		KitchenStatusHistory history = new KitchenStatusHistory();
		history.id = UUID.randomUUID();
		history.restaurantId = order.getRestaurantId();
		history.kitchenOrderId = order.getId();
		history.fromStatus = from;
		history.toStatus = to;
		history.actorUserId = actorUserId;
		history.commandId = commandId;
		history.occurredAt = occurredAt;
		return history;
	}

	/**
	 * The change to CANCELLED caused by the Order service's approved decision. There is no user and no command: the
	 * Order service's event id says what caused it.
	 */
	public static KitchenStatusHistory cancelled(KitchenOrder order, KitchenOrderStatus from, UUID sourceEventId, String reason,
			Instant occurredAt) {
		KitchenStatusHistory history = new KitchenStatusHistory();
		history.id = UUID.randomUUID();
		history.restaurantId = order.getRestaurantId();
		history.kitchenOrderId = order.getId();
		history.fromStatus = from;
		history.toStatus = KitchenOrderStatus.CANCELLED;
		history.sourceEventId = sourceEventId;
		history.reason = reason;
		history.occurredAt = occurredAt;
		return history;
	}

	/** Is this entry the result of the given command, issued by the same actor, for this order and target status? */
	public boolean isResultOf(UUID commandId, UUID actorUserId, UUID kitchenOrderId, KitchenOrderStatus target) {
		return commandId != null && commandId.equals(this.commandId) && actorUserId != null
				&& actorUserId.equals(this.actorUserId) && kitchenOrderId.equals(this.kitchenOrderId) && target == toStatus;
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
