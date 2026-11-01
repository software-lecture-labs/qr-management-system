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

/** A normalized ingredient selection of a cart line: a removed standard ingredient or a paid extra. */
@Entity
@Table(name = "cart_item_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItemOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_item_id", nullable = false, updatable = false)
    private CartItem cartItem;

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

    @Column(name = "unit_price_delta_minor_snapshot", nullable = false)
    private long unitPriceDeltaMinorSnapshot;

    public CartItemOption(CartItem cartItem, UUID optionId, SelectionAction action, int quantity,
                          String nameSnapshot, long unitPriceDeltaMinorSnapshot) {
        this.id = UUID.randomUUID();
        this.cartItem = cartItem;
        this.restaurantId = cartItem.getRestaurantId();
        this.optionId = optionId;
        this.action = action;
        this.quantity = quantity;
        this.nameSnapshot = nameSnapshot;
        this.unitPriceDeltaMinorSnapshot = unitPriceDeltaMinorSnapshot;
    }

    /** Only the last-validated price may change; the selection itself (option, action, quantity) never does. */
    public void refreshPrice(long unitPriceDeltaMinor) {
        this.unitPriceDeltaMinorSnapshot = unitPriceDeltaMinor;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CartItemOption option && id.equals(option.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
