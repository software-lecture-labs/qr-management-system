package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.CartStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.MoneyConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A guest's cart. One ACTIVE cart per guest session (DB-enforced). Rows are created through
 * {@code CartRepository#insertActiveIfAbsent}; the entity only mutates them.
 *
 * <p>The entity guards its own invariants, so no caller has to remember them:
 * <ul>
 *   <li>Status changes follow {@link CartStatus#canTransitionTo}: only an ACTIVE cart changes, and it leaves ACTIVE
 *       once (CHECKED_OUT or EXPIRED). Items and currency of a cart that is no longer ACTIVE cannot be changed.</li>
 *   <li>The currency is empty or a supported ISO 4217 code ({@link MoneyConverter#normalizeCurrency}); it is checked on
 *       every change and again whenever the row is loaded or stored, so an invalid stored value is refused loudly
 *       instead of circulating. It cannot change while the cart holds items.</li>
 * </ul>
 * Who may transition a cart, and when, is the use cases' decision; they run under the cart's row lock, so two
 * concurrent callers cannot both succeed (the second one finds a non-ACTIVE cart and is refused).
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false, updatable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CartStatus status;

    @Column(length = 3)
    private String currency;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A set (not a bag): fetching items together with their options must not duplicate lines. */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    @Getter(AccessLevel.NONE)
    private Set<CartItem> items = new LinkedHashSet<>();

    /** A read-only view: lines change only through {@link #addItem}, {@link #removeItem} and {@link #clearItems}. */
    public Set<CartItem> getItems() {
        return Collections.unmodifiableSet(items);
    }

    public Optional<CartItem> findItem(UUID cartItemId) {
        return items.stream().filter(item -> item.getId().equals(cartItemId)).findFirst();
    }

    public Optional<CartItem> findItem(UUID productId, String selectionKey) {
        return items.stream()
                .filter(item -> item.getProductId().equals(productId) && item.getSelectionKey().equals(selectionKey))
                .findFirst();
    }

    public void addItem(CartItem item) {
        requireModifiable();
        items.add(item);
    }

    public void removeItem(CartItem item) {
        requireModifiable();
        items.remove(item);
    }

    public void clearItems() {
        requireModifiable();
        items.clear();
    }

    /**
     * Marks the cart as turned into an order; it can never be changed or checked out again. An empty cart, or one
     * without a currency, is not a cart that can be ordered.
     */
    public void checkOut(Instant now) {
        transitionTo(CartStatus.CHECKED_OUT, now);
    }

    /**
     * Sets the currency of an empty cart (or repeats the one it already has). The code is normalized and validated; an
     * unsupported code is refused with {@link IllegalArgumentException}, never replaced by a default currency.
     */
    public void useCurrency(String newCurrency) {
        requireModifiable();
        String normalized = MoneyConverter.normalizeCurrency(newCurrency);
        if (!items.isEmpty() && !normalized.equals(currency)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                    "The currency of a cart that already has items cannot change");
        }
        this.currency = normalized;
    }

    private void transitionTo(CartStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw notModifiable();
        }
        if (target == CartStatus.CHECKED_OUT && (items.isEmpty() || currency == null)) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "The cart is empty");
        }
        this.status = target;
        touch(now);
    }

    private void requireModifiable() {
        if (!status.isModifiable()) {
            throw notModifiable();
        }
    }

    private BusinessException notModifiable() {
        return status == CartStatus.CHECKED_OUT
                ? new BusinessException(ErrorCode.CART_ALREADY_CHECKED_OUT, "This cart has already been ordered")
                : new BusinessException(ErrorCode.CONFLICT, "This cart can no longer be used");
    }

    /**
     * Runs when a row is read and before it is written. Storage cannot be trusted more than a request: a currency
     * that is not a supported ISO 4217 code (the database only checks the three-letter shape) is never accepted
     * silently or repaired, the operation fails.
     */
    @PostLoad
    @PrePersist
    @PreUpdate
    void requireValidStoredCurrency() {
        if (currency != null && !MoneyConverter.isCanonicalCurrency(currency)) {
            throw new IllegalStateException("cart " + id + " has an unsupported currency code");
        }
    }

    /**
     * Marks the cart modified so the optimistic {@code version} advances even if only children changed.
     * {@code updatedAt} is guaranteed to differ from its previous value, so the entity is always dirty.
     */
    public void touch(Instant now) {
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }
}
