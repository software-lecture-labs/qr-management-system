package com.restofy.waiter.infrastructure.client.qr;

import java.util.List;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;

/**
 * DRAFT QR contract v0: one page of tables in a stable order (table id ascending). The total is a wrapper
 * so a missing total cannot silently mean "no tables".
 */
public record QrTablePageDto(List<QrTableDto> items, int page, int size, Long totalElements) {

    public QrTablePageDto {
        items = requiredList(items, "items");
        nonNegative(page, "page");
        nonNegative(required(totalElements, "totalElements"), "totalElements");
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
    }
}
