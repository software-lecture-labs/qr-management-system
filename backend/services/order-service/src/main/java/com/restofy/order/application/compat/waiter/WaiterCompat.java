package com.restofy.order.application.compat.waiter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The shapes the Waiter Service's DRAFT Order contract (v0) expects for money and cancellations, produced from the Order
 * Service's own model. Nothing here changes how the Order Service stores or exposes its data: amounts stay integer minor
 * units everywhere else, and this is a separate, explicitly named projection (no endpoint serves it yet).
 *
 * <p>The component names equal the Waiter's JSON names, so a future endpoint can serialise them as they are. The Waiter
 * reads with {@code fail-on-unknown-properties=true}: a field it does not know breaks it, which is why everything the Waiter
 * cannot express stays in {@link OrderFinance.Breakdown}, in {@link WaiterCancellation#reasons()} and in {@link
 * UnrepresentableCancellation}, and is never silently dropped or guessed.
 */
public final class WaiterCompat {

    private WaiterCompat() {
    }

    /**
     * Order-level money in major units. {@code originalGrossTotal} is the tax-inclusive total at checkout and never
     * changes; {@code cancelledGrossTotal} is the gross of the cancellations that were APPLIED (a waiting or rejected
     * request is not in it); {@code payableTotal} is what the order is worth after those cancellations, that is original
     * minus cancelled. It is NOT the remaining debt: an order that is already paid has a payable total and no debt (the
     * debt, the collected, the refund required and the refund made are separate figures in {@link OrderFinance.Breakdown}).
     */
    public record WaiterOrderMoney(String currency, BigDecimal originalGrossTotal, BigDecimal cancelledGrossTotal,
                                   BigDecimal payableTotal) {
    }

    /** One order line. {@code unitGrossPrice} is tax-inclusive and already includes the paid extras of the line. */
    public record WaiterItem(UUID id, UUID productId, String productName, int quantity, int cancelledQuantity,
                             BigDecimal unitGrossPrice, BigDecimal lineGrossTotal, String note,
                             List<WaiterOption> options) {
    }

    /** One chosen option; {@code unitPriceDelta} is informational (it is already inside the unit price of the line). */
    public record WaiterOption(String name, String action, int quantity, BigDecimal unitPriceDelta) {
    }

    public enum WaiterCancellationSource { CUSTOMER, KITCHEN, WAITER }

    public enum WaiterCancellationStatus { REQUESTED, APPROVED, REJECTED }

    public record WaiterCancelledItem(UUID orderItemId, int quantity) {
    }

    /** Who gave a reason, in the Order Service's own terms: kind GUEST, STAFF or SERVICE, and the role of a staff member. */
    public record ReasonAuthor(String kind, String role) {
    }

    public record WaiterReason(String code, String text, ReasonAuthor author) {
    }

    /** The Waiter's exact cancellation shape; it exists only when the reasons fit its one code and one text. */
    public record LegacyCancellationView(UUID id, WaiterCancellationSource source, WaiterCancellationStatus status,
                                         String reasonCode, String reasonText, Instant requestedAt, Instant decidedAt,
                                         List<WaiterCancelledItem> items) {
    }

    /**
     * One cancellation with ALL its reasons. The Waiter's shape has one {@code reasonCode} and one {@code reasonText}; use
     * {@link #legacy()} only if that is all you can show: it is empty when the reasons do not fit, and nobody picks a
     * reason for you. {@code items} are the lines and units the request covers (requested, applied or rejected as
     * {@code status} says).
     */
    public record WaiterCancellation(UUID id, WaiterCancellationSource source, WaiterCancellationStatus status,
                                     Instant requestedAt, Instant decidedAt, List<WaiterCancelledItem> items,
                                     List<WaiterReason> reasons) {

        /** The Waiter's one-code-one-text shape, or empty when it cannot hold the reasons without loss. */
        public Optional<LegacyCancellationView> legacy() {
            long codes = reasons.stream().filter(reason -> reason.code() != null).count();
            long texts = reasons.stream().filter(reason -> reason.text() != null).count();
            if (codes > 1 || texts > 1) {
                return Optional.empty();
            }
            String code = reasons.stream().map(WaiterReason::code).filter(Objects::nonNull).findFirst().orElse(null);
            String text = reasons.stream().map(WaiterReason::text).filter(Objects::nonNull).findFirst().orElse(null);
            return Optional.of(new LegacyCancellationView(id, source, status, code, text, requestedAt, decidedAt, items));
        }
    }

    /** A cancellation the Waiter's model cannot represent faithfully; reported, never mapped to a wrong actor. */
    public record UnrepresentableCancellation(UUID id, String reason, String requestedByType, String requestedByRole) {
    }

    public record WaiterCancellations(List<WaiterCancellation> cancellations, List<UnrepresentableCancellation> unrepresentable) {

        /** True when every cancellation of the order is in {@code cancellations}. */
        public boolean complete() {
            return unrepresentable.isEmpty();
        }
    }

    /** Everything this projection holds about one order of one restaurant. */
    public record WaiterOrderCompat(UUID orderId, UUID restaurantId, WaiterOrderMoney money, List<WaiterItem> items,
                                    WaiterCancellations cancellations, OrderFinance.Breakdown finance) {
    }
}
