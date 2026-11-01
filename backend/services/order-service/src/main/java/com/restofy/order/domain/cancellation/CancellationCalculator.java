package com.restofy.order.domain.cancellation;

import com.restofy.order.domain.enums.CancellationScope;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Works out what a cancellation cancels and what it is worth, from the immutable order snapshot (unit price and VAT rate
 * of each line) and the units already cancelled. Money is BIGINT minor units and the order snapshot is never changed.
 *
 * <p>Additivity is exact: the gross of cancelled units is {@code unit * quantity}; the net of the REMAINING units of a
 * line is computed by the same rounding as at checkout, and the net of the cancelled part is the difference between the
 * remaining net before and after. So cancelling a line in several steps always adds up to the line's original net and
 * vat, never a cent more or less, and {@code net + vat == gross} holds at every step.
 */
public final class CancellationCalculator {

    private static final String ORDER_ITEM_ID = "orderItemId";

    /** One order line as it is now: its immutable price, its ordered and already cancelled units. */
    public record Line(UUID orderItemId, UUID productId, String productName, int orderedQuantity, int cancelledQuantity,
                       long unitGrossMinor, int taxRateBps) {

        public int remainingQuantity() {
            return orderedQuantity - cancelledQuantity;
        }
    }

    public record Selection(UUID orderItemId, int quantity) {
    }

    public record ItemCancellation(UUID orderItemId, UUID productId, String productName, int quantity,
                                   int remainingAfter, long unitGrossMinor, int taxRateBps, long grossMinor,
                                   long netMinor, long vatMinor) {
    }

    /**
     * @param payableBeforeMinor gross of every active unit before the cancellation
     * @param payableAfterMinor  gross of every active unit after it (what the order is worth afterwards)
     */
    public record Result(CancellationScope scope, List<ItemCancellation> items, long grossMinor, long netMinor,
                         long vatMinor, long payableBeforeMinor, long payableAfterMinor, int remainingUnitsAfter) {
    }

    private CancellationCalculator() {
    }

    /**
     * @param cancelAll true cancels every unit that is still active; otherwise {@code selections} says which lines and
     *                  how many units
     * @throws BusinessException ORDER_ALREADY_CANCELLED when nothing is left to cancel, UNKNOWN_ORDER_ITEM,
     *                           CANCEL_QUANTITY_EXCEEDED or VALIDATION_ERROR for a bad selection
     */
    public static Result calculate(List<Line> lines, boolean cancelAll, List<Selection> selections) {
        long payableBefore = 0L;
        int remainingBefore = 0;
        for (Line line : lines) {
            payableBefore = Math.addExact(payableBefore,
                    Math.multiplyExact(line.unitGrossMinor(), (long) line.remainingQuantity()));
            remainingBefore += line.remainingQuantity();
        }
        if (remainingBefore == 0) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED,
                    "Every unit of this order is already cancelled");
        }

        Map<UUID, Integer> wanted = quantitiesToCancel(lines, cancelAll, selections);

        List<ItemCancellation> items = new ArrayList<>();
        long gross = 0L;
        long net = 0L;
        long vat = 0L;
        int remainingAfter = remainingBefore;
        for (Line line : lines) {
            Integer quantity = wanted.get(line.orderItemId());
            if (quantity == null) {
                continue;
            }
            long cancelledGross = Math.multiplyExact(line.unitGrossMinor(), (long) quantity);
            long cancelledNet = netOfRemaining(line, line.remainingQuantity())
                    - netOfRemaining(line, line.remainingQuantity() - quantity);
            long cancelledVat = cancelledGross - cancelledNet;
            items.add(new ItemCancellation(line.orderItemId(), line.productId(), line.productName(), quantity,
                    line.remainingQuantity() - quantity, line.unitGrossMinor(), line.taxRateBps(), cancelledGross,
                    cancelledNet, cancelledVat));
            gross = Math.addExact(gross, cancelledGross);
            net = Math.addExact(net, cancelledNet);
            vat = Math.addExact(vat, cancelledVat);
            remainingAfter -= quantity;
        }
        CancellationScope scope = remainingAfter == 0 ? CancellationScope.FULL : CancellationScope.PARTIAL;
        return new Result(scope, List.copyOf(items), gross, net, vat, payableBefore, payableBefore - gross,
                remainingAfter);
    }

    private static Map<UUID, Integer> quantitiesToCancel(List<Line> lines, boolean cancelAll,
                                                          List<Selection> selections) {
        Map<UUID, Integer> wanted = new HashMap<>();
        if (cancelAll) {
            lines.stream().filter(line -> line.remainingQuantity() > 0)
                    .forEach(line -> wanted.put(line.orderItemId(), line.remainingQuantity()));
            return wanted;
        }
        Map<UUID, Line> byId = new HashMap<>();
        lines.forEach(line -> byId.put(line.orderItemId(), line));
        Set<UUID> seen = new HashSet<>();
        for (Selection selection : selections) {
            validateSelection(byId.get(selection.orderItemId()), selection, seen);
            wanted.put(selection.orderItemId(), selection.quantity());
        }
        return wanted;
    }

    private static void validateSelection(Line line, Selection selection, Set<UUID> seen) {
        if (line == null) {
            throw new BusinessException(ErrorCode.UNKNOWN_ORDER_ITEM, "This order has no such line",
                    Map.of(ORDER_ITEM_ID, selection.orderItemId()));
        }
        if (!seen.add(selection.orderItemId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A line may be selected only once",
                    Map.of("parameter", "items", ORDER_ITEM_ID, selection.orderItemId()));
        }
        if (selection.quantity() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A quantity must be at least 1",
                    Map.of("parameter", "items.quantity", ORDER_ITEM_ID, selection.orderItemId()));
        }
        if (selection.quantity() > line.remainingQuantity()) {
            throw new BusinessException(ErrorCode.CANCEL_QUANTITY_EXCEEDED,
                    "Only " + line.remainingQuantity() + " unit(s) of this line are still active",
                    Map.of(ORDER_ITEM_ID, selection.orderItemId(), "remainingQuantity", line.remainingQuantity(),
                            "requestedQuantity", selection.quantity()));
        }
    }

    /** VAT-exclusive net of the given number of still-active units of a line, by the checkout rounding. */
    private static long netOfRemaining(Line line, int units) {
        if (units <= 0) {
            return 0L;
        }
        return PriceCalculator.priceLine(line.unitGrossMinor(), units, line.taxRateBps()).lineNetMinor();
    }
}
