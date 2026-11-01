package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.SelectionAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Immutable snapshot of one removed ingredient or paid extra, as priced at checkout. */
@Entity
@Table(name = "order_item_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
    private OrderItem orderItem;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "option_id", nullable = false, updatable = false)
    private UUID optionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SelectionAction action;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "name_snapshot", nullable = false, updatable = false)
    private String nameSnapshot;

    @Column(name = "unit_price_delta_minor", nullable = false, updatable = false)
    private long unitPriceDeltaMinor;

    public OrderItemOption(OrderItem orderItem, UUID optionId, SelectionAction action, int quantity,
                           String nameSnapshot, long unitPriceDeltaMinor) {
        this.id = UUID.randomUUID();
        this.orderItem = orderItem;
        this.restaurantId = orderItem.getRestaurantId();
        this.optionId = optionId;
        this.action = action;
        this.quantity = quantity;
        this.nameSnapshot = nameSnapshot;
        this.unitPriceDeltaMinor = unitPriceDeltaMinor;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof OrderItemOption option && id.equals(option.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
