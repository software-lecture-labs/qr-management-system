package com.restofy.order.domain.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single currency rule every money-bearing type of the Order Service shares. */
class MoneyConverterCurrencyTests {

    @ParameterizedTest
    @ValueSource(strings = {"TRY", "USD", "EUR", "GBP", "JPY", "KWD", "CHF"})
    void everyRealPaymentCurrencyIsAcceptedNotOnlyTry(String code) {
        assertEquals(code, MoneyConverter.normalizeCurrency(code));
        assertTrue(MoneyConverter.isCanonicalCurrency(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"try", "Try", " TRY", "TRY ", "\tusd\n"})
    void caseAndSurroundingWhitespaceAreNormalizedToTheCanonicalCode(String code) {
        assertEquals(code.strip().toUpperCase(Locale.ROOT), MoneyConverter.normalizeCurrency(code));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "TR", "TRYY", "TR1", "T-Y", "ZZZ", "AAA", "ABC", "12", "TRÝ", "ТRY"})
    void aMissingMalformedOrUnknownCodeIsRefusedNeverReplacedByADefault(String code) {
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.normalizeCurrency(code));
        assertFalse(MoneyConverter.isCanonicalCurrency(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXX", "XTS", "XAU", "XAG", "XDR", "XBA"})
    void isoCodesThatAreNotPaymentCurrenciesAreRefused(String code) {
        // XXX = "no currency", XTS = testing, the rest are precious metals or accounting units: none has a minor unit
        assertThrows(IllegalArgumentException.class, () -> MoneyConverter.normalizeCurrency(code));
    }

    @Test
    void onlyAlreadyCanonicalCodesAreCanonical() {
        assertTrue(MoneyConverter.isCanonicalCurrency("TRY"));
        assertFalse(MoneyConverter.isCanonicalCurrency("try"));
        assertFalse(MoneyConverter.isCanonicalCurrency(" TRY"));
        assertFalse(MoneyConverter.isCanonicalCurrency(null));
    }

    @Test
    void theBasisPointBaseIsAHundredPercent() {
        assertEquals(10_000, MoneyConverter.BPS_BASE);
        assertEquals(MoneyConverter.BPS_BASE, MoneyConverter.percentToBps(new BigDecimal("100")));
    }
}
