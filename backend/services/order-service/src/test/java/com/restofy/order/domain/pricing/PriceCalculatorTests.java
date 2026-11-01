package com.restofy.order.domain.pricing;

import com.restofy.order.domain.pricing.PriceCalculator.LinePrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceCalculatorTests {

    @Test
    void pricesALineWithQuantityAndVat() {
        LinePrice price = PriceCalculator.priceLine(25_000L, 2, 1_000);
        assertEquals(25_000L, price.unitGrossMinor());
        assertEquals(50_000L, price.lineGrossMinor());
        assertEquals(45_455L, price.lineNetMinor());
        assertEquals(4_545L, price.lineVatMinor());
    }

    @ParameterizedTest(name = "gross {0} at {1} bps -> net {2}, vat {3}")
    @CsvSource({
            "0,    1000, 0,  0",
            "1,    1000, 1,  0",     // 0.909 rounds up
            "5,    1000, 5,  0",     // 4.545 rounds up
            "11,   1000, 10, 1",     // exact
            "3,    10000, 2, 1",     // exactly 1.5 -> half rounds up
            "100,  0,    100, 0",    // 0% VAT
            "118,  1800, 100, 18",
            "12000, 2000, 10000, 2000",
            "9999, 800,  9258, 741"  // 9258.33 rounds down
    })
    void roundsHalfUpPerLine(long gross, int bps, long expectedNet, long expectedVat) {
        LinePrice price = PriceCalculator.priceLine(gross, 1, bps);
        assertEquals(expectedNet, price.lineNetMinor());
        assertEquals(expectedVat, price.lineVatMinor());
    }

    @Test
    void netPlusVatAlwaysEqualsGross() {
        for (int rate : new int[]{0, 100, 800, 1_000, 1_800, 2_000, 10_000}) {
            for (long unit = 0; unit <= 3_000; unit++) {
                for (int quantity : new int[]{1, 3, 7}) {
                    LinePrice price = PriceCalculator.priceLine(unit, quantity, rate);
                    assertEquals(price.lineGrossMinor(), price.lineNetMinor() + price.lineVatMinor(),
                            "unit=" + unit + " qty=" + quantity + " rate=" + rate);
                }
            }
        }
    }

    @Test
    void unknownTaxRateLeavesNetAndVatUnset() {
        LinePrice price = PriceCalculator.priceLine(1_000L, 3, null);
        assertEquals(3_000L, price.lineGrossMinor());
        assertNull(price.lineNetMinor());
        assertNull(price.lineVatMinor());
    }

    @Test
    void differentRatesAreRoundedPerLineAndThenSummed() {
        LinePrice tenPercent = PriceCalculator.priceLine(1_005L, 1, 1_000);   // net 914 vat 91
        LinePrice twentyPercent = PriceCalculator.priceLine(1_005L, 1, 2_000); // net 838 vat 167
        assertEquals(914L, tenPercent.lineNetMinor());
        assertEquals(838L, twentyPercent.lineNetMinor());
        long gross = tenPercent.lineGrossMinor() + twentyPercent.lineGrossMinor();
        long net = tenPercent.lineNetMinor() + twentyPercent.lineNetMinor();
        long vat = tenPercent.lineVatMinor() + twentyPercent.lineVatMinor();
        assertEquals(gross, net + vat);
    }

    @Test
    void addsExtrasToTheUnitPrice() {
        assertEquals(34_000L, PriceCalculator.unitGross(25_000L, 9_000L));
    }

    @Test
    void overflowIsDetectedInsteadOfWrappingAround() {
        assertThrows(ArithmeticException.class, () -> PriceCalculator.priceLine(Long.MAX_VALUE / 2, 3, 1_000));
        assertThrows(ArithmeticException.class, () -> PriceCalculator.priceLine(Long.MAX_VALUE / 5, 2, 1_000));
        assertThrows(ArithmeticException.class, () -> PriceCalculator.unitGross(Long.MAX_VALUE, 1L));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(-1L, 1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(100L, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(100L, -2, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.netOfGross(100L, -1));
    }

    // ---- order totals ----------------------------------------------------------------------------------------

    @Test
    void orderTotalsAreTheExactSumOfTheRoundedLines() {
        LinePrice burgers = PriceCalculator.priceLine(34_000L, 3, 1_000);  // gross 102000 net 92727 vat 9273
        LinePrice salads = PriceCalculator.priceLine(9_000L, 2, 2_000);    // gross 18000  net 15000 vat 3000
        PriceCalculator.OrderAmounts total = PriceCalculator.totalOf(List.of(burgers, salads));

        assertEquals(92_727L, burgers.lineNetMinor());
        assertEquals(120_000L, total.grossMinor());
        assertEquals(107_727L, total.netMinor());
        assertEquals(12_273L, total.vatMinor());
        assertEquals(total.grossMinor(), total.netMinor() + total.vatMinor());
    }

    @Test
    void rounding_per_line_is_not_the_same_as_rounding_the_sum_and_the_policy_is_per_line() {
        // three lines of 5 minor units at 10%: each net rounds 4.545 -> 5 (vat 0); a rounded sum would give 14 / 1
        List<LinePrice> lines = List.of(PriceCalculator.priceLine(5, 1, 1_000),
                PriceCalculator.priceLine(5, 1, 1_000), PriceCalculator.priceLine(5, 1, 1_000));
        PriceCalculator.OrderAmounts total = PriceCalculator.totalOf(lines);
        assertEquals(15L, total.netMinor());
        assertEquals(0L, total.vatMinor());
        assertEquals(15L, total.grossMinor());
    }

    @Test
    void emptyOrdersTotalZero() {
        PriceCalculator.OrderAmounts total = PriceCalculator.totalOf(List.of());
        assertEquals(0L, total.grossMinor() + total.netMinor() + total.vatMinor());
    }

    @Test
    void totalingRefusesLinesWithUnknownTax() {
        LinePrice unknown = PriceCalculator.priceLine(1_000L, 1, null);
        List<LinePrice> lines = List.of(unknown);
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.totalOf(lines));
    }

    @Test
    void totalingDetectsOverflowInsteadOfWrappingAround() {
        LinePrice huge = new LinePrice(1, Long.MAX_VALUE - 1, Long.MAX_VALUE - 2, 1L);
        List<LinePrice> lines = List.of(huge, huge);
        assertThrows(ArithmeticException.class, () -> PriceCalculator.totalOf(lines));
    }

    @Test
    void invariantHoldsForOrdersMadeOfManyMixedLines() {
        int[] rates = {0, 100, 800, 1_000, 1_800, 2_000};
        for (int seed = 1; seed <= 500; seed++) {
            List<LinePrice> lines = new ArrayList<>();
            for (int i = 0; i < 1 + seed % 6; i++) {
                lines.add(PriceCalculator.priceLine(37L * seed + 11L * i, 1 + (seed + i) % 9, rates[(seed + i) % rates.length]));
            }
            PriceCalculator.OrderAmounts total = PriceCalculator.totalOf(lines);
            assertEquals(lines.stream().mapToLong(LinePrice::lineGrossMinor).sum(), total.grossMinor());
            assertEquals(total.grossMinor(), total.netMinor() + total.vatMinor(), "seed " + seed);
        }
    }
}
