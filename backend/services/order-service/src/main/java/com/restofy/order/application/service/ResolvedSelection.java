package com.restofy.order.application.service;

import com.restofy.order.domain.enums.SelectionAction;

import java.util.List;
import java.util.UUID;

/**
 * Validated, normalized ingredient selection of one cart line. Everything here is derived from the trusted
 * catalog snapshot, never from client-supplied names or prices.
 *
 * @param options             removed ingredients and paid extras, ordered by action then option id
 * @param extrasUnitDeltaMinor sum of (extra quantity * extra unit price delta) added to the base unit price
 * @param selectionKey        stable hash of product-independent selections and note; equal keys merge into one line
 */
public record ResolvedSelection(List<ResolvedOption> options, long extrasUnitDeltaMinor, String note,
                                String selectionKey) {

    public record ResolvedOption(UUID optionId, SelectionAction action, int quantity, String name,
                                 long unitPriceDeltaMinor) {
    }
}
