package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.WasteReason;
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
import java.util.Objects;
import java.util.UUID;

/**
 * One manual waste entry. Insert-only: it is never edited. It does not change any stock quantity and does not close
 * a product. {@code recordedAt} is the server's time and {@code recordedByUserId} the verified user, never the client's.
 */
@Entity
@Table(name = "waste_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WasteRecord implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "product_name_snapshot", updatable = false, length = 200)
	private String productNameSnapshot;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, updatable = false, length = 40)
	private WasteReason reasonCode;

	@Column(updatable = false, length = 500)
	private String description;

	@Column(name = "order_id", updatable = false)
	private UUID orderId;

	@Column(name = "recorded_at", nullable = false, updatable = false)
	private Instant recordedAt;

	@Column(name = "recorded_by_user_id", nullable = false, updatable = false)
	private UUID recordedByUserId;

	@Transient
	private boolean isNew = true;

	public record NewEntry(UUID restaurantId, UUID commandId, UUID productId, String productNameSnapshot, int quantity,
			WasteReason reasonCode, String description, UUID orderId, Instant recordedAt, UUID recordedByUserId) {
	}

	public static WasteRecord create(NewEntry entry) {
		WasteRecord wasteRecord = new WasteRecord();
		wasteRecord.id = UUID.randomUUID();
		wasteRecord.restaurantId = entry.restaurantId();
		wasteRecord.commandId = entry.commandId();
		wasteRecord.productId = entry.productId();
		wasteRecord.productNameSnapshot = entry.productNameSnapshot();
		wasteRecord.quantity = entry.quantity();
		wasteRecord.reasonCode = entry.reasonCode();
		wasteRecord.description = entry.description();
		wasteRecord.orderId = entry.orderId();
		wasteRecord.recordedAt = entry.recordedAt();
		wasteRecord.recordedByUserId = entry.recordedByUserId();
		return wasteRecord;
	}

	/** Is this exactly the entry that is being sent again (same product, quantity, reason, text, order and user)? */
	public boolean isSameEntry(UUID productId, int quantity, WasteReason reasonCode, String description, UUID orderId,
			UUID recordedByUserId) {
		return this.productId.equals(productId) && this.quantity == quantity && this.reasonCode == reasonCode
				&& Objects.equals(this.description, description) && Objects.equals(this.orderId, orderId)
				&& this.recordedByUserId.equals(recordedByUserId);
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
