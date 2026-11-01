package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Confirms the cart the guest saw. The cart, table and session come from the authenticated guest session; the
 * client only states the cart version and total it agreed to, and the server re-prices everything itself.
 *
 * <p>{@code expectedPriceFingerprint} is optional. When present it is the {@code priceFingerprint} of the cart view the
 * guest reviewed: it binds the confirmation to that cart version and to every live price component, so a changed price
 * is never accepted unseen and a stale confirmation cannot be reused. The server recomputes it; it is never trusted.
 *
 * <p>{@code acknowledgePriceChanges} is the guest's explicit "I saw that these prices changed and I accept the new
 * ones". It only matters when a price differs from what the cart last validated: then BOTH a matching
 * {@code expectedPriceFingerprint} and {@code acknowledgePriceChanges=true} are required, otherwise the order is refused
 * (409 PRICE_CHANGED) and nothing is written. When no price changed, neither field is needed.
 */
public record CheckoutRequest(
        @NotNull @Min(0) Long expectedCartVersion,
        @NotNull @Min(0) Long expectedTotalMinor,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String note,
        @Pattern(regexp = "[0-9a-f]{64}", message = "must be a 64 character lowercase hex digest")
        String expectedPriceFingerprint,
        Boolean acknowledgePriceChanges) {
}
