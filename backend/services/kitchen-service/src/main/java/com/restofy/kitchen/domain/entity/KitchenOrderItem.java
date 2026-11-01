package com.restofy.kitchen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Read-only order line snapshot. V1 has no per-item preparation state. */
@Entity
@Table(name = "kitchen_order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KitchenOrderItem {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "kitchen_order_id", nullable = false, updatable = false)
	private KitchenOrder kitchenOrder;

	@Column(name = "line_number", nullable = false, updatable = false)
	private int lineNumber;

	@Column(name = "order_item_id", nullable = false, updatable = false)
	private UUID orderItemId;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "product_name_snapshot", nullable = false, updatable = false, length = 200)
	private String productNameSnapshot;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Column(name = "cancelled_quantity", nullable = false)
	private int cancelledQuantity;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "options_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
	private String optionsSnapshot;

	@Column(name = "note_snapshot", updatable = false, length = 1000)
	private String noteSnapshot;

	KitchenOrderItem(KitchenOrder order, int lineNumber, UUID orderItemId, UUID productId, String productName,
			int quantity, String optionsSnapshot, String note) {
		this.id = UUID.randomUUID();
		this.restaurantId = order.getRestaurantId();
		this.kitchenOrder = order;
		this.lineNumber = lineNumber;
		this.orderItemId = orderItemId;
		this.productId = productId;
		this.productNameSnapshot = productName;
		this.quantity = quantity;
		this.optionsSnapshot = optionsSnapshot;
		this.noteSnapshot = note;
	}
}
