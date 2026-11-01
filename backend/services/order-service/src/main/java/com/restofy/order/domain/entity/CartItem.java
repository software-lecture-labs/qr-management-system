package com.restofy.order.domain.entity;

import com.restofy.order.domain.pricing.MoneyConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One cart line. The stored name/price/tax are the values last validated against the Menu catalog;
 * the cart view recomputes live prices and flags differences. They are never authoritative for an order.
 */
@Entity
@Table(name = "cart_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    /** Options of several lines are loaded together, this many lines per query. */
    private static final int OPTION_FETCH_BATCH_SIZE = 50;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "selection_key", nullable = false, updatable = false, length = 64)
    private String selectionKey;

    @Column(length = 300)
    private String note;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "unit_gross_minor_snapshot", nullable = false)
    private long unitGrossMinorSnapshot;

    @Column(name = "tax_rate_bps_snapshot")
    private Integer taxRateBpsSnapshot;

    @Column(name = "price_version_snapshot", length = 64)
    private String priceVersionSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cartItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = OPTION_FETCH_BATCH_SIZE)
    private Set<CartItemOption> options = new LinkedHashSet<>();

    public CartItem(Cart cart, UUID productId, int quantity, String selectionKey, String note,
                    String productNameSnapshot, String currency, long unitGrossMinorSnapshot,
                    Integer taxRateBpsSnapshot, String priceVersionSnapshot, Instant now) {
        this.id = UUID.randomUUID();
        this.cart = cart;
        this.restaurantId = cart.getRestaurantId();
        this.productId = productId;
        this.quantity = quantity;
        this.selectionKey = selectionKey;
        this.note = note;
        this.productNameSnapshot = productNameSnapshot;
        this.currency = MoneyConverter.normalizeCurrency(currency);
        this.unitGrossMinorSnapshot = unitGrossMinorSnapshot;
        this.taxRateBpsSnapshot = taxRateBpsSnapshot;
        this.priceVersionSnapshot = priceVersionSnapshot;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addOption(CartItemOption option) {
        options.add(option);
    }

    public void changeQuantity(int newQuantity, Instant now) {
        this.quantity = newQuantity;
        this.updatedAt = now;
    }

    /** Refreshes the last-validated catalog values (used when the same line is added again). */
    public void refreshSnapshot(String name, long unitGrossMinor, Integer taxRateBps, String priceVersion,
                                Instant now) {
        this.productNameSnapshot = name;
        this.unitGrossMinorSnapshot = unitGrossMinor;
        this.taxRateBpsSnapshot = taxRateBps;
        this.priceVersionSnapshot = priceVersion;
        this.updatedAt = now;
    }

    /** Re-reads the unit price of every paid extra from the validated selection (same options, current prices). */
    public void refreshOptionPrices(Map<UUID, Long> unitPriceDeltaByOptionId) {
        for (CartItemOption stored : options) {
            Long current = unitPriceDeltaByOptionId.get(stored.getOptionId());
            if (current != null) {
                stored.refreshPrice(current);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CartItem item && id.equals(item.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
