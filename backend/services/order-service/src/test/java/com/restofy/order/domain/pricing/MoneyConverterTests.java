package com.restofy.order.domain.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyConverterTests {

    private static final long CAP = 1_000_000_000_000L;

    @ParameterizedTest(name = "{0} {1} -> {2} minor")
    @CsvSource({
            "125.50,   TRY, 12550",
            "125.5000, TRY, 12550",   // four stored decimals that are all zero beyond the second are exact
            "0,        TRY, 0",
            "0.01,     TRY, 1",
            "99999.99, TRY, 9999999",
            "500,      JPY, 500",     // JPY has no minor unit
            "1.005,    KWD, 1005"     // KWD has three decimals
    })
    void convertsExactValuesUsingTheCurrencysOwnScale(String amount, String currency, long expectedMinor) {
        assertEquals(expectedMinor, MoneyConverter.toMinor(new BigDecimal(amount), currency, CAP));
    }

    @ParameterizedTest
    @CsvSource({"12.505, TRY", "0.001, TRY", "12.5000001, TRY", "1.5, JPY", "1.0005, KWD"})
    void rejectsValuesThatWouldNeedRounding(String amount, String currency) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MoneyConverter.toMinor(new BigDecimal(amount), currency, CAP));
        assertEquals(true, error.getMessage().contains("not rounded silently"));
    }

    @Test
    void rejectsNegativeUnknownAndOversizedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMinor(new BigDecimal("-0.01"), "TRY", CAP));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMinor(new BigDecimal("1"), "XXX", CAP));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMinor(new BigDecimal("1"), "tr", CAP));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMinor(new BigDecimal("1"), null, CAP));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMinor(null, "TRY", CAP));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyConverter.toMinor(new BigDecimal("10000000000.01"), "TRY", CAP));
        assertEquals(CAP, MoneyConverter.toMinor(new BigDecimal("10000000000.00"), "TRY", CAP));
    }

    @ParameterizedTest
    @CsvSource({"10, 1000", "10.00, 1000", "10.0000, 1000", "8.5, 850", "0, 0", "100, 10000", "18, 1800", "0.01, 1"})
    void convertsVatPercentagesToBasisPoints(String percent, int bps) {
        assertEquals(bps, MoneyConverter.percentToBps(new BigDecimal(percent)));
    }

    @ParameterizedTest
    @CsvSource({"8.555", "10.001", "-1", "100.01", "101"})
    void rejectsVatPercentagesThatCannotBeRepresentedOrAreOutOfRange(String percent) {
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.percentToBps(new BigDecimal(percent)));
    }

    // ---- the reverse direction, for the Waiter compatibility projection (exact, never rounded) -------------------------

    @ParameterizedTest(name = "{0} minor {1} -> {2}")
    @CsvSource({
            "12550,   TRY, 125.50",
            "199,     TRY, 1.99",
            "1,       TRY, 0.01",
            "0,       TRY, 0.00",
            "100,     TRY, 1.00",
            "500,     JPY, 500",       // no minor unit: nothing to divide
            "1005,    KWD, 1.005",     // three decimals
            "9999999, TRY, 99999.99"
    })
    void convertsMinorUnitsToTheCurrencysOwnScaleWithoutAnyRounding(long minor, String currency, String expected) {
        BigDecimal major = MoneyConverter.toMajor(minor, currency);
        assertEquals(expected, major.toPlainString());
        assertEquals(Currency.getInstance(currency).getDefaultFractionDigits(), major.scale());
    }

    @Test
    void theLargestPossibleAmountKeepsEveryDigit() {
        assertEquals("92233720368547758.07", MoneyConverter.toMajor(Long.MAX_VALUE, "TRY").toPlainString());
        assertEquals(Long.MAX_VALUE, MoneyConverter.toMajor(Long.MAX_VALUE, "TRY").movePointRight(2).longValueExact());
    }

    @ParameterizedTest
    @CsvSource({"0, TRY", "1, TRY", "7, TRY", "99, TRY", "12550, TRY", "100000000000, TRY", "7, KWD", "1234, JPY"})
    void backAndForthNeverChangesTheStoredAmount(long minor, String currency) {
        assertEquals(minor, MoneyConverter.toMinor(MoneyConverter.toMajor(minor, currency), currency, Long.MAX_VALUE));
    }

    @Test
    void anAmountIsNeverGuessedForAnUnknownCurrencyOrAnAmountThatCannotExist() {
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(100, "ZZZ"));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(100, "XXX"));   // no minor unit
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(100, "try"));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(100, null));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(-1, "TRY"));
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.toMajor(Long.MIN_VALUE, "TRY"));
    }
}
