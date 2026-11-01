package com.restofy.order.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderNumberGeneratorTests {

    @Test
    void formatsAsPrefixAndSixDigitsThatGrowWithoutTruncation() {
        assertEquals("ORD-000001", OrderNumberGenerator.format(1));
        assertEquals("ORD-000042", OrderNumberGenerator.format(42));
        assertEquals("ORD-999999", OrderNumberGenerator.format(999_999));
        assertEquals("ORD-1000000", OrderNumberGenerator.format(1_000_000));
        assertEquals("ORD-9223372036854775807", OrderNumberGenerator.format(Long.MAX_VALUE));
    }

    @Test
    void everyFormattedNumberFitsTheColumnAndIsOrderedByValueWithinTheSameWidth() {
        for (long value : new long[]{1, 10, 999_999, 1_000_000, Long.MAX_VALUE}) {
            assertTrue(OrderNumberGenerator.format(value).length() <= 40);
        }
        assertTrue(OrderNumberGenerator.format(7).compareTo(OrderNumberGenerator.format(8)) < 0);
    }
}
