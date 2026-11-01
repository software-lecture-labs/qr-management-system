package com.restofy.order.application.port;

import com.restofy.order.domain.pricing.MoneyConverter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted catalog view of one product at a point in time.
 *
 * @param published   visible on the menu
 * @param available   currently sellable (not out of stock / temporarily closed)
 * @param taxRateBps  VAT rate in basis points (1000 = 10%); {@code null} when the catalog gave none
 * @param priceVersion opaque version of the product's price/option data, when the catalog provides one; compared by
 *                     the cart reconciliation so a changed version is a changed price even if the amounts look equal
 */
public record ProductSnapshot(UUID productId, String name, boolean published, boolean available, String currency,
                              long basePriceGrossMinor, Integer taxRateBps, List<ProductOption> options,
                              String priceVersion) {

    /** A snapshot from a catalog that does not provide a price version (the Menu Service does not yet). */
    public ProductSnapshot(UUID productId, String name, boolean published, boolean available, String currency,
                           long basePriceGrossMinor, Integer taxRateBps, List<ProductOption> options) {
        this(productId, name, published, available, currency, basePriceGrossMinor, taxRateBps, options, null);
    }

    /** Upper bound for any single catalog amount; keeps line/total arithmetic far from long overflow. */
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    public static final int MAX_OPTION_QUANTITY = 1_000;
    public static final int MAX_PRODUCT_NAME_LENGTH = 200;
    public static final int MAX_OPTION_NAME_LENGTH = 150;
    public static final int MAX_PRICE_VERSION_LENGTH = 64;

    public ProductSnapshot {
        Objects.requireNonNull(productId, "productId");
        if (name == null || name.isBlank() || name.length() > MAX_PRODUCT_NAME_LENGTH) {
            throw new IllegalArgumentException("product name must be 1.." + MAX_PRODUCT_NAME_LENGTH + " characters");
        }
        if (!MoneyConverter.isCanonicalCurrency(currency)) {
            throw new IllegalArgumentException("currency must be a supported upper-case ISO 4217 code");
        }
        if (basePriceGrossMinor < 0 || basePriceGrossMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("price must be within 0.." + MAX_AMOUNT_MINOR);
        }
        if (taxRateBps != null && (taxRateBps < 0 || taxRateBps > MoneyConverter.BPS_BASE)) {
            throw new IllegalArgumentException("taxRateBps must be within 0.." + MoneyConverter.BPS_BASE);
        }
        if (priceVersion != null && (priceVersion.isBlank() || priceVersion.length() > MAX_PRICE_VERSION_LENGTH)) {
            throw new IllegalArgumentException(
                    "priceVersion must be 1.." + MAX_PRICE_VERSION_LENGTH + " characters when present");
        }
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean sellable() {
        return published && available;
    }

    /**
     * An ingredient/extra the product offers. INCLUDED options are standard ingredients (removable only when
     * {@code minQuantity == 0}); ADD options are paid extras selectable up to {@code maxQuantity}.
     */
    public record ProductOption(UUID optionId, String name, OptionType type, long priceDeltaMinor,
                                int minQuantity, int maxQuantity, boolean active) {

        public ProductOption {
            Objects.requireNonNull(optionId, "optionId");
            Objects.requireNonNull(type, "type");
            if (name == null || name.isBlank() || name.length() > MAX_OPTION_NAME_LENGTH) {
                throw new IllegalArgumentException("option name must be 1.." + MAX_OPTION_NAME_LENGTH + " characters");
            }
            if (priceDeltaMinor < 0 || priceDeltaMinor > MAX_AMOUNT_MINOR) {
                throw new IllegalArgumentException("option price delta must be within 0.." + MAX_AMOUNT_MINOR);
            }
            if (minQuantity < 0 || maxQuantity < minQuantity || maxQuantity > MAX_OPTION_QUANTITY) {
                throw new IllegalArgumentException("option quantity bounds are invalid");
            }
        }

        public boolean removable() {
            return type == OptionType.INCLUDED && active && minQuantity == 0;
        }
    }

    public enum OptionType { INCLUDED, ADD }
}
