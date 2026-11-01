package com.restofy.waiter.application.service;

import com.restofy.waiter.infrastructure.client.qr.QrTableDto;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable table order: table numbers compare naturally ("2" before "10", "A2" before "A10"), then by the raw
 * number and finally by id, so two tables never swap places between pages.
 */
final class NaturalTableOrder {

    static final Comparator<QrTableDto> COMPARATOR = Comparator
            .comparing(QrTableDto::tableNumber, NaturalTableOrder::compareNatural)
            .thenComparing(QrTableDto::tableNumber)
            .thenComparing(QrTableDto::id);

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    private NaturalTableOrder() {
    }

    static int compareNatural(String left, String right) {
        Matcher a = CHUNK.matcher(left);
        Matcher b = CHUNK.matcher(right);
        while (a.find() && b.find()) {
            String x = a.group();
            String y = b.group();
            boolean numeric = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0));
            int result = numeric ? new BigInteger(x).compareTo(new BigInteger(y)) : x.compareToIgnoreCase(y);
            if (result != 0) return result;
        }
        return Integer.compare(left.length(), right.length());
    }
}
