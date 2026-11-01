package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalTableOrderTests {

    private static QrTableDto table(String number, UUID id) {
        return new QrTableDto(id, UUID.randomUUID(), number, null, null, TableOperationalStatus.AVAILABLE, true, 0L, null);
    }

    private static List<String> sorted(String... numbers) {
        List<QrTableDto> tables = new ArrayList<>();
        for (String number : numbers) tables.add(table(number, UUID.randomUUID()));
        tables.sort(NaturalTableOrder.COMPARATOR);
        return tables.stream().map(QrTableDto::tableNumber).toList();
    }

    @Test
    void numbersCompareByValueAndTextByChunk() {
        assertEquals(List.of("1", "2", "9", "10", "11", "100"), sorted("100", "10", "2", "11", "1", "9"));
        assertEquals(List.of("A1", "A2", "A10", "B1"), sorted("B1", "A10", "A2", "A1"));
        assertEquals(List.of("Bahçe 2", "Bahçe 10", "Teras 1"), sorted("Teras 1", "Bahçe 10", "Bahçe 2"));
    }

    @Test
    void equalLookingNumbersStayInAStableDeterministicOrder() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<QrTableDto> tables = new ArrayList<>(List.of(table("01", high), table("1", low), table("01", low)));
        List<UUID> expected = tables.stream().sorted(NaturalTableOrder.COMPARATOR).map(QrTableDto::id).toList();
        for (int shuffle = 0; shuffle < 5; shuffle++) {
            Collections.shuffle(tables);
            assertEquals(expected, tables.stream().sorted(NaturalTableOrder.COMPARATOR).map(QrTableDto::id).toList());
        }
        assertTrue(NaturalTableOrder.compareNatural("1", "01") < 0 || NaturalTableOrder.compareNatural("1", "01") > 0);
    }

    @Test
    void prefixesSortBeforeLongerNames() {
        assertEquals(List.of("A", "A1", "A1b"), sorted("A1b", "A1", "A"));
    }
}
