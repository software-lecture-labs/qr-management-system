package com.restofy.order.domain.pricing;

import java.util.List;

/**
 * Single money engine for the Order Service. Amounts are integer minor units; prices are tax-inclusive.
 * Rounding policy (fixed): per line, VAT-exclusive net = ROUND_HALF_UP(gross * 10000 / (10000 + rateBps)),
 * vat = gross - net, so {@code net + vat == gross} always holds. Order totals are the exact sums of the lines.
 */
public final class PriceCalculator {

    private static final long BPS_BASE = MoneyConverter.BPS_BASE;

    private PriceCalculator() {
    }

    /**
     * Prices one line.
     *
     * @param unitGrossMinor tax-inclusive unit price including paid extras
     * @param taxRateBps     VAT rate in basis points, or {@code null} when unknown (net/vat are then {@code null})
     * @throws ArithmeticException if the amount does not fit into a long
     */
    public static LinePrice priceLine(long unitGrossMinor, int quantity, Integer taxRateBps) {
        if (unitGrossMinor < 0 || quantity < 1) {
            throw new IllegalArgumentException("unit price must be >= 0 and quantity >= 1");
        }
        long lineGross = Math.multiplyExact(unitGrossMinor, (long) quantity);
        if (taxRateBps == null) {
            return new LinePrice(unitGrossMinor, lineGross, null, null);
        }
        long net = netOfGross(lineGross, taxRateBps);
        return new LinePrice(unitGrossMinor, lineGross, net, lineGross - net);
    }

    /** Tax-inclusive unit price: base + sum(extra quantity * extra unit delta). */
    public static long unitGross(long basePriceGrossMinor, long extrasUnitDeltaMinor) {
        return Math.addExact(basePriceGrossMinor, extrasUnitDeltaMinor);
    }

    /**
     * Order totals are the exact integer sums of the line amounts (never recomputed from a rounded total), so
     * {@code net + vat == gross} holds for the order exactly as it does for each line.
     *
     * @throws IllegalArgumentException if a line has no tax split (an order may not be priced with unknown tax)
     * @throws ArithmeticException      if a total does not fit into a long
     */
    public static OrderAmounts totalOf(List<LinePrice> lines) {
        long net = 0L;
        long vat = 0L;
        long gross = 0L;
        for (LinePrice line : lines) {
            if (line.lineNetMinor() == null || line.lineVatMinor() == null) {
                throw new IllegalArgumentException("every line needs a known tax split to total an order");
            }
            net = Math.addExact(net, line.lineNetMinor());
            vat = Math.addExact(vat, line.lineVatMinor());
            gross = Math.addExact(gross, line.lineGrossMinor());
        }
        if (net + vat != gross) {
            throw new IllegalStateException("net + vat must equal gross");
        }
        return new OrderAmounts(net, vat, gross);
    }

    static long netOfGross(long gross, int taxRateBps) {
        if (taxRateBps < 0) {
            throw new IllegalArgumentException("taxRateBps must not be negative");
        }
        long divisor = BPS_BASE + taxRateBps;
        // gross >= 0, so floor((2*num + div) / (2*div)) is ROUND_HALF_UP(num / div).
        long numerator = Math.multiplyExact(gross, BPS_BASE);
        return Math.floorDiv(Math.addExact(Math.multiplyExact(numerator, 2L), divisor), 2L * divisor);
    }

    public record OrderAmounts(long netMinor, long vatMinor, long grossMinor) {
    }

    /** @param lineNetMinor and lineVatMinor are {@code null} when the tax rate is unknown */
    public record LinePrice(long unitGrossMinor, long lineGrossMinor, Long lineNetMinor, Long lineVatMinor) {
    }
}
