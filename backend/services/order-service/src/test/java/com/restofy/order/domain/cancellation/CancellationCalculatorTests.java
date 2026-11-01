package com.restofy.order.domain.cancellation;

import com.restofy.order.domain.cancellation.CancellationCalculator.ItemCancellation;
import com.restofy.order.domain.cancellation.CancellationCalculator.Line;
import com.restofy.order.domain.cancellation.CancellationCalculator.Result;
import com.restofy.order.domain.cancellation.CancellationCalculator.Selection;
import com.restofy.order.domain.enums.CancellationScope;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancellationCalculatorTests {

    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID SALAD = UUID.randomUUID();

    private static Line burger(int ordered, int cancelled) {
        return new Line(BURGER, UUID.randomUUID(), "Burger", ordered, cancelled, 25_000L, 1_000);
    }

    private static Line salad(int ordered, int cancelled) {
        return new Line(SALAD, UUID.randomUUID(), "Salad", ordered, cancelled, 9_000L, 2_000);
    }

    @Test
    void cancellingEverythingIsFullAndAmountsAreExact() {
        Result result = CancellationCalculator.calculate(List.of(burger(2, 0), salad(1, 0)), true, List.of());
        assertEquals(CancellationScope.FULL, result.scope());
        assertEquals(2 * 25_000L + 9_000L, result.grossMinor());
        assertEquals(result.grossMinor(), result.netMinor() + result.vatMinor());
        assertEquals(0L, result.payableAfterMinor());
        assertEquals(0, result.remainingUnitsAfter());
        assertEquals(2, result.items().size());
    }

    @Test
    void cancelAllTakesOnlyTheUnitsThatAreStillActive() {
        Result result = CancellationCalculator.calculate(List.of(burger(3, 2), salad(1, 1)), true, List.of());
        assertEquals(1, result.items().size());
        assertEquals(1, result.items().get(0).quantity());
        assertEquals(25_000L, result.grossMinor());
        assertEquals(CancellationScope.FULL, result.scope());
    }

    @Test
    void aPartialCancellationLeavesTheRestActive() {
        Result result = CancellationCalculator.calculate(List.of(burger(3, 0), salad(1, 0)), false,
                List.of(new Selection(BURGER, 2)));
        assertEquals(CancellationScope.PARTIAL, result.scope());
        assertEquals(50_000L, result.grossMinor());
        assertEquals(3 * 25_000L + 9_000L, result.payableBeforeMinor());
        assertEquals(25_000L + 9_000L, result.payableAfterMinor());
        assertEquals(2, result.remainingUnitsAfter());
        ItemCancellation item = result.items().get(0);
        assertEquals(1, item.remainingAfter());
    }

    @Test
    void selectingEverythingThatIsLeftBecomesAFullCancellation() {
        Result result = CancellationCalculator.calculate(List.of(burger(2, 1), salad(1, 0)), false,
                List.of(new Selection(BURGER, 1), new Selection(SALAD, 1)));
        assertEquals(CancellationScope.FULL, result.scope());
        assertEquals(0L, result.payableAfterMinor());
    }

    @Test
    void stepsAddUpToTheOriginalNetAndVatToTheLastMinorUnit() {
        // 7 units at a price whose VAT split does not divide evenly: cancel them one at a time
        long unit = 3_333L;
        int bps = 1_800;
        int ordered = 7;
        long originalNet = PriceCalculator.priceLine(unit, ordered, bps).lineNetMinor();
        long originalVat = PriceCalculator.priceLine(unit, ordered, bps).lineVatMinor();
        long net = 0;
        long vat = 0;
        long gross = 0;
        for (int cancelled = 0; cancelled < ordered; cancelled++) {
            Line line = new Line(BURGER, UUID.randomUUID(), "Odd", ordered, cancelled, unit, bps);
            Result step = CancellationCalculator.calculate(List.of(line), false, List.of(new Selection(BURGER, 1)));
            assertEquals(step.grossMinor(), step.netMinor() + step.vatMinor(), "net + vat == gross at every step");
            net += step.netMinor();
            vat += step.vatMinor();
            gross += step.grossMinor();
        }
        assertEquals(originalNet, net);
        assertEquals(originalVat, vat);
        assertEquals(unit * ordered, gross);
    }

    @Test
    void splittingACancellationDoesNotChangeTheTotalAmounts() {
        Line whole = burger(5, 0);
        Result once = CancellationCalculator.calculate(List.of(whole), false, List.of(new Selection(BURGER, 5)));
        Result first = CancellationCalculator.calculate(List.of(whole), false, List.of(new Selection(BURGER, 2)));
        Result second = CancellationCalculator.calculate(List.of(burger(5, 2)), false, List.of(new Selection(BURGER, 3)));
        assertEquals(once.grossMinor(), first.grossMinor() + second.grossMinor());
        assertEquals(once.netMinor(), first.netMinor() + second.netMinor());
        assertEquals(once.vatMinor(), first.vatMinor() + second.vatMinor());
    }

    @Test
    void moreThanTheActiveQuantityIsRefused() {
        List<Line> lines = List.of(burger(3, 2));
        List<Selection> selections = List.of(new Selection(BURGER, 2));
        BusinessException refused = assertThrows(BusinessException.class, () -> CancellationCalculator.calculate(
                lines, false, selections));
        assertEquals(ErrorCode.CANCEL_QUANTITY_EXCEEDED, refused.code());
        assertEquals(1, refused.details().get("remainingQuantity"));
    }

    @Test
    void aLineThatIsNotInTheOrderIsRefused() {
        List<Line> lines = List.of(burger(1, 0));
        List<Selection> selections = List.of(new Selection(UUID.randomUUID(), 1));
        BusinessException refused = assertThrows(BusinessException.class, () -> CancellationCalculator.calculate(
                lines, false, selections));
        assertEquals(ErrorCode.UNKNOWN_ORDER_ITEM, refused.code());
    }

    @Test
    void aLineSelectedTwiceAndAZeroOrNegativeQuantityAreRefused() {
        List<Line> lines = List.of(burger(3, 0));
        List<Selection> duplicateSelections = List.of(new Selection(BURGER, 1), new Selection(BURGER, 1));
        BusinessException twice = assertThrows(BusinessException.class, () -> CancellationCalculator.calculate(
                lines, false, duplicateSelections));
        assertEquals(ErrorCode.VALIDATION_ERROR, twice.code());
        for (int quantity : new int[]{0, -1}) {
            List<Selection> selections = List.of(new Selection(BURGER, quantity));
            BusinessException bad = assertThrows(BusinessException.class, () -> CancellationCalculator.calculate(
                    lines, false, selections));
            assertEquals(ErrorCode.VALIDATION_ERROR, bad.code());
        }
    }

    @Test
    void anOrderWithNothingLeftCannotBeCancelledAgain() {
        List<Line> lines = List.of(burger(2, 2));
        for (boolean all : new boolean[]{true, false}) {
            List<Selection> selections = all ? List.of() : List.of(new Selection(BURGER, 1));
            BusinessException refused = assertThrows(BusinessException.class, () -> CancellationCalculator.calculate(
                    lines, all, selections));
            assertEquals(ErrorCode.ORDER_ALREADY_CANCELLED, refused.code());
        }
    }

    @Test
    void remainingAmountsNeverGoNegativeWhateverTheOrderOfCancellations() {
        List<Line> lines = new ArrayList<>(List.of(burger(4, 0), salad(3, 0)));
        long payable = 4 * 25_000L + 3 * 9_000L;
        Result a = CancellationCalculator.calculate(lines, false, List.of(new Selection(BURGER, 1), new Selection(SALAD, 3)));
        assertEquals(payable - a.grossMinor(), a.payableAfterMinor());
        assertEquals(3 * 25_000L, a.payableAfterMinor());
    }
}
