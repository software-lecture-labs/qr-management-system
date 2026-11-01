package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.pricing.MoneyConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A placed order. The initial amounts and every {@link OrderItem} snapshot are fixed at checkout; only the
 * statuses and {@code currentDueMinor} change afterwards. Fulfillment and payment are independent lifecycles.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "order_no", nullable = false, updatable = false, length = 40)
    private String orderNo;

    @Column(name = "cart_id", nullable = false, updatable = false)
    private UUID cartId;

    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false, updatable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false)
    private FulfillmentStatus fulfillmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "initial_net_minor", nullable = false, updatable = false)
    private long initialNetMinor;

    @Column(name = "initial_vat_minor", nullable = false, updatable = false)
    private long initialVatMinor;

    @Column(name = "initial_gross_minor", nullable = false, updatable = false)
    private long initialGrossMinor;

    @Column(name = "payment_event_at")
    private Instant paymentEventAt;

    @Column(name = "current_due_minor", nullable = false)
    private long currentDueMinor;

    @Column(updatable = false, length = 300)
    private String note;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("lineNo ASC")
    private Set<OrderItem> items = new LinkedHashSet<>();

    /** Creates a freshly placed order: RECEIVED, payment NOT_REQUESTED, due equal to the initial gross. */
    public Order(UUID id, String orderNo, UUID restaurantId, UUID cartId, UUID tableId, UUID tableSessionId,
                 UUID guestSessionId, String currency, long netMinor, long vatMinor, long grossMinor, String note,
                 Instant now) {
        this.id = id;
        this.orderNo = orderNo;
        this.restaurantId = restaurantId;
        this.cartId = cartId;
        this.tableId = tableId;
        this.tableSessionId = tableSessionId;
        this.guestSessionId = guestSessionId;
        this.fulfillmentStatus = FulfillmentStatus.RECEIVED;
        this.paymentStatus = PaymentStatus.NOT_REQUESTED;
        this.currency = MoneyConverter.normalizeCurrency(currency);
        this.initialNetMinor = netMinor;
        this.initialVatMinor = vatMinor;
        this.initialGrossMinor = grossMinor;
        this.currentDueMinor = grossMinor;
        this.note = note;
        this.placedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Records the new fulfillment status. Whether the step is allowed is decided by the caller
     * ({@code FulfillmentTransitions}); the database repeats the rule as a guard trigger.
     */
    public void changeFulfillmentStatus(FulfillmentStatus target, Instant now) {
        this.fulfillmentStatus = target;
        this.updatedAt = now;
    }

    /**
     * Records the new payment status and when the Payment Service says it happened. Whether the move is allowed is
     * decided by the caller ({@code PaymentTransitions}); the database repeats the rule as a guard trigger. When the
     * payment is PAID the remaining debt ({@code currentDueMinor}) becomes 0; the initial snapshot, the history and the
     * verified amount (kept in the history row and the event) are what record how much was collected.
     */
    public void changePaymentStatus(PaymentStatus target, Instant eventAt, Instant now) {
        this.paymentStatus = target;
        // never moves backwards: a late event that is applied must not make newer ones look stale-free again
        this.paymentEventAt = paymentEventAt == null || eventAt.isAfter(paymentEventAt) ? eventAt : paymentEventAt;
        if (target == PaymentStatus.PAID) {
            // the verified collection settles what was owed; the original amounts are untouched
            this.currentDueMinor = 0L;
        }
        this.updatedAt = now;
    }

    /**
     * Records a finalised cancellation: what the order still owes, and (when nothing is left) the CANCELLED status. The
     * initial amounts and every line snapshot stay as they were. Whether the cancellation is allowed is decided by
     * {@code CancellationPolicy}; the database repeats the status rules as guard triggers.
     */
    public void applyCancellation(long newDueMinor, boolean fullyCancelled, Instant now) {
        this.currentDueMinor = newDueMinor;
        if (fullyCancelled) {
            this.fulfillmentStatus = FulfillmentStatus.CANCELLED;
        }
        this.updatedAt = now;
    }

    /**
     * Closes the payment axis of an order that was cancelled before any payment attempt (NOT_REQUESTED to CANCELLED, the
     * move the V45 payment guard reserves for this). It is not a payment event and claims nothing about money.
     */
    public void closePaymentAxis(Instant now) {
        this.paymentStatus = PaymentStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }
}
