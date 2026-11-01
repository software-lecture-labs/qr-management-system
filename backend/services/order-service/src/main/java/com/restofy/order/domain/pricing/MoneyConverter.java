package com.restofy.order.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * The only place where external decimal values (for example the Menu Service's {@code numeric(19,4)} prices and
 * {@code numeric(7,4)} VAT percentages) become the Order Service's integer representation.
 *
 * <p>Policy: conversion is exact or it fails. A value that would lose precision (for instance 12.505 TRY, which has
 * a third decimal although TRY has two) is rejected instead of being rounded silently, because rounding a price
 * would change what the guest is charged. Negative values and values above the catalog cap are rejected too.
 * {@code BigDecimal} is used only here, as an input type; stored and calculated amounts are always {@code long}.
 */
public final class MoneyConverter {

    /** Basis points in one whole: a tax rate of 100% is 10,000 bps. */
    public static final int BPS_BASE = 10_000;

    private MoneyConverter() {
    }

    /**
     * The one definition of a currency this service can charge in: an ISO 4217 code that has a minor unit. Surrounding
     * whitespace and letter case are normalized ({@code " try "} becomes {@code "TRY"}); nothing else is guessed. A
     * code that is unknown or not a real payment currency (the ISO placeholders {@code XXX} "no currency", {@code XTS}
     * "testing", metals and fund units have no minor unit) is refused, and it is never replaced by a default such as
     * TRY. Every money-bearing entity and conversion in this service goes through this rule.
     *
     * @return the canonical upper-case code
     * @throws IllegalArgumentException if the code is null, blank, unknown or has no minor unit
     */
    public static String normalizeCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        String canonical = currencyCode.strip().toUpperCase(Locale.ROOT);
        fractionDigits(canonical);
        return canonical;
    }

    /** True if {@link #normalizeCurrency(String)} accepts the code exactly as it is, i.e. it is already canonical. */
    public static boolean isCanonicalCurrency(String currencyCode) {
        try {
            return normalizeCurrency(currencyCode).equals(currencyCode);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * Converts a major-unit amount (125.50) into minor units (12550) using the currency's own number of fraction
     * digits.
     *
     * @throws IllegalArgumentException if the currency is unknown, the value is negative or too large, or it has more
     *                                  decimals than the currency allows
     */
    public static long toMinor(BigDecimal majorAmount, String currencyCode, long maxMinor) {
        if (majorAmount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        int digits = fractionDigits(currencyCode);
        BigDecimal scaled;
        try {
            scaled = majorAmount.setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("amount has more decimals than " + currencyCode + " allows (" + digits
                    + "); it is not rounded silently");
        }
        if (scaled.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        BigDecimal minor = scaled.movePointRight(digits);
        if (minor.compareTo(BigDecimal.valueOf(maxMinor)) > 0) {
            throw new IllegalArgumentException("amount exceeds the allowed maximum");
        }
        return minor.longValueExact();
    }

    /**
     * Converts minor units (12550) into the major-unit decimal of the currency (125.50) for a consumer that expects
     * decimals (the Waiter compatibility projection). Always exact: the scale is the currency's own number of fraction
     * digits (TRY 2, JPY 0, KWD 3), nothing is rounded, nothing is lost, {@code double} is never involved. The stored minor
     * amount is not touched. An unknown currency or a currency without a minor unit is refused instead of guessed, and so
     * is a negative amount (the Order Service stores none).
     *
     * @throws IllegalArgumentException if the currency is unknown or has no minor unit, or the amount is negative
     */
    public static BigDecimal toMajor(long minorAmount, String currencyCode) {
        if (minorAmount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return BigDecimal.valueOf(minorAmount, fractionDigits(currencyCode));
    }

    /**
     * Converts a VAT percentage (10, 10.00, 8.5) into basis points (1000, 1000, 850). More than two decimals of a
     * percent (for example 8.555) cannot be represented and is rejected.
     */
    public static int percentToBps(BigDecimal percent) {
        if (percent == null) {
            throw new IllegalArgumentException("tax rate is required");
        }
        BigDecimal bps;
        try {
            bps = percent.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("tax rate has more precision than basis points; not rounded silently");
        }
        if (bps.signum() < 0 || bps.compareTo(BigDecimal.valueOf(BPS_BASE)) > 0) {
            throw new IllegalArgumentException("tax rate must be within 0..100 percent");
        }
        return bps.intValueExact();
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            if (digits < 0) {
                throw new IllegalArgumentException("currency has no minor unit: " + currencyCode);
            }
            return digits;
        } catch (IllegalArgumentException | NullPointerException _) {
            throw new IllegalArgumentException("unknown currency: " + currencyCode);
        }
    }
}
