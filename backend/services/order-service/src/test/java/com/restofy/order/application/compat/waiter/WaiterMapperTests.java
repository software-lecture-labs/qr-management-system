package com.restofy.order.application.compat.waiter;

import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationSource;
import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterCancellationStatus;
import com.restofy.order.application.compat.waiter.WaiterMapper.CancellationData;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.infrastructure.persistence.CancellationStore;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.order.application.compat.waiter.CompatFixtures.item;
import static com.restofy.order.application.compat.waiter.CompatFixtures.itemRow;
import static com.restofy.order.application.compat.waiter.CompatFixtures.option;
import static com.restofy.order.application.compat.waiter.CompatFixtures.order;
import static com.restofy.order.application.compat.waiter.CompatFixtures.reason;
import static com.restofy.order.application.compat.waiter.CompatFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaiterMapperTests {

    private static List<CancellationData> one(Row row, List<CancellationStore.ReasonRow> reasons) {
        return List.of(new CancellationData(row, List.of(itemRow(UUID.randomUUID(), 2)), reasons));
    }

    private static Row requestedBy(String type, String id, String role) {
        return row(order("TRY", new long[]{100, 1}), "REQUESTED", type, id, role, 0, 0, 0);
    }

    // ---- money ------------------------------------------------------------------------------------------------------

    @Test
    void theOrderLevelMoneyIsTheExactDecimalOfTheStoredMinorAmounts() {
        var finance = new OrderFinance.Breakdown("TRY", 52_000, 18_000, 34_000, 0, 34_000, 0, 0);

        var money = WaiterMapper.money(finance);

        assertEquals("TRY", money.currency());
        assertEquals("520.00", money.originalGrossTotal().toPlainString());
        assertEquals("180.00", money.cancelledGrossTotal().toPlainString());
        assertEquals("340.00", money.payableTotal().toPlainString());
    }

    @Test
    void payableIsWhatTheOrderIsWorthNotWhatIsStillOwed() {
        // paid in full and then partly cancelled: it is worth 270.00, nothing is owed, 520.00 was collected
        var finance = new OrderFinance.Breakdown("TRY", 52_000, 25_000, 27_000, 52_000, 0, 25_000, 0);

        var money = WaiterMapper.money(finance);

        assertEquals("270.00", money.payableTotal().toPlainString());
        assertEquals(0, finance.outstandingDueMinor(), "the debt is a different figure and is not what payableTotal means");
    }

    @Test
    void theLinesCarryTheSnapshotPricesAndTheExtrasAreAlreadyInTheUnitPrice() {
        Order order = order("TRY", new long[]{27_500, 2});
        option(item(order, 0), "Extra cheese", SelectionAction.ADD, 1, 2_500);
        option(item(order, 0), "Onion", SelectionAction.REMOVE, 1, 0);
        item(order, 0).applyCancellation(1);

        var items = WaiterMapper.items(order);

        assertEquals(1, items.size());
        var line = items.get(0);
        assertEquals(2, line.quantity());
        assertEquals(1, line.cancelledQuantity());
        assertEquals("275.00", line.unitGrossPrice().toPlainString());
        assertEquals("550.00", line.lineGrossTotal().toPlainString(), "the snapshot of the ordered quantity, not of what is left");
        assertEquals(2, line.options().size());
        assertEquals("Extra cheese", line.options().get(0).name());
        assertEquals("ADD", line.options().get(0).action());
        assertEquals("25.00", line.options().get(0).unitPriceDelta().toPlainString());
        assertEquals("0.00", line.options().get(1).unitPriceDelta().toPlainString());
    }

    @Test
    void aCurrencyWithoutAUsableMinorUnitFailsInsteadOfGuessing() {
        var finance = new OrderFinance.Breakdown("XXX", 100, 0, 100, 0, 100, 0, 0);
        Assertions.assertThrows(IllegalArgumentException.class, () -> WaiterMapper.money(finance));
    }

    // ---- who asked ----------------------------------------------------------------------------------------------------

    @Test
    void aGuestIsACustomer() {
        assertEquals(Optional.of(WaiterCancellationSource.CUSTOMER), WaiterMapper.source("GUEST", null, "guest-session"));
    }

    @Test
    void aStaffMemberIsAWaiterOnlyWhenTheVerifiedRoleIsWaiter() {
        assertEquals(Optional.of(WaiterCancellationSource.WAITER), WaiterMapper.source("STAFF", "WAITER", "u1"));
        assertEquals(Optional.empty(), WaiterMapper.source("STAFF", "ADMIN", "u1"), "an admin is never shown as a waiter");
        assertEquals(Optional.empty(), WaiterMapper.source("STAFF", "KITCHEN", "u1"));
        assertEquals(Optional.empty(), WaiterMapper.source("STAFF", null, "u1"));
        assertEquals(Optional.empty(), WaiterMapper.source("STAFF", "waiter", "u1"), "a role is an identifier, matched exactly");
    }

    @Test
    void aServiceIsKitchenOnlyForTheVerifiedKitchenService() {
        assertEquals(Optional.of(WaiterCancellationSource.KITCHEN), WaiterMapper.source("SERVICE", "KITCHEN", "KITCHEN"));
        assertEquals(Optional.empty(), WaiterMapper.source("SERVICE", "PAYMENT", "PAYMENT"));
        assertEquals(Optional.empty(), WaiterMapper.source("SERVICE", "KITCHEN", "PAYMENT"), "role without the identity");
        assertEquals(Optional.empty(), WaiterMapper.source("SERVICE", "WAITER", "KITCHEN"));
        assertEquals(Optional.empty(), WaiterMapper.source("SERVICE", null, "KITCHEN"));
        assertEquals(Optional.empty(), WaiterMapper.source("SERVICE", "KITCHEN", null));
    }

    @Test
    void anythingElseHasNoWaiterEquivalent() {
        assertEquals(Optional.empty(), WaiterMapper.source("SYSTEM", null, null));
        assertEquals(Optional.empty(), WaiterMapper.source("ROBOT", "WAITER", "u"));
        assertEquals(Optional.empty(), WaiterMapper.source(null, null, null));
    }

    @Test
    void anAdminsCancellationIsReportedAsUnrepresentableAndIsNeverAWaitersOne() {
        var adminRow = requestedBy("STAFF", UUID.randomUUID().toString(), "ADMIN");
        var waiterRow = requestedBy("STAFF", UUID.randomUUID().toString(), "WAITER");
        var data = List.of(new CancellationData(adminRow, List.of(), List.of()),
                new CancellationData(waiterRow, List.of(), List.of()));

        var result = WaiterMapper.cancellations(data);

        assertFalse(result.complete());
        assertEquals(1, result.unrepresentable().size());
        assertEquals(adminRow.id(), result.unrepresentable().get(0).id());
        assertEquals("REQUESTER_HAS_NO_WAITER_EQUIVALENT", result.unrepresentable().get(0).reason());
        assertEquals("ADMIN", result.unrepresentable().get(0).requestedByRole());
        assertEquals(1, result.cancellations().size());
        assertEquals(waiterRow.id(), result.cancellations().get(0).id());
        assertEquals(WaiterCancellationSource.WAITER, result.cancellations().get(0).source());
    }

    @Test
    void theStaffMemberAServiceClaimedIsNeverTheRequester() {
        UUID claimedAdmin = UUID.randomUUID();
        Row kitchen = new Row(UUID.randomUUID(), CompatFixtures.RESTAURANT, UUID.randomUUID(), "FULL", "APPLIED", "SERVICE",
                "KITCHEN", "KITCHEN", claimedAdmin, CompatFixtures.NOW, false, "RECEIVED", "NOT_REQUESTED", "KITCHEN",
                "SYSTEM", null, null, CompatFixtures.NOW, null, null, "TRY", 100, 100, 100, 0, 0, 0, 0, 0, null, null, 0,
                CompatFixtures.NOW);

        var result = WaiterMapper.cancellations(List.of(new CancellationData(kitchen, List.of(), List.of())));

        assertTrue(result.complete());
        assertEquals(WaiterCancellationSource.KITCHEN, result.cancellations().get(0).source());
    }

    // ---- state --------------------------------------------------------------------------------------------------------

    @Test
    void requestedAndRejectedAreTheSameAndAppliedIsApproved() {
        assertEquals(Optional.of(WaiterCancellationStatus.REQUESTED), WaiterMapper.status("REQUESTED"));
        assertEquals(Optional.of(WaiterCancellationStatus.REJECTED), WaiterMapper.status("REJECTED"));
        assertEquals(Optional.of(WaiterCancellationStatus.APPROVED), WaiterMapper.status("APPLIED"));
        assertEquals(Optional.empty(), WaiterMapper.status("APPROVED"), "Order has no such state");
        assertEquals(Optional.empty(), WaiterMapper.status("CANCELLED"));
        assertEquals(Optional.empty(), WaiterMapper.status(null));
    }

    @Test
    void aWaitingRequestHasNoDecisionTimeAndARejectedOneHasOne() {
        var waiting = requestedBy("GUEST", "g", null);
        var rejected = row(order("TRY", new long[]{100, 1}), "REJECTED", "GUEST", "g", null, 0, 0, 0);

        var result = WaiterMapper.cancellations(one(waiting, List.of()));
        var other = WaiterMapper.cancellations(one(rejected, List.of()));

        assertEquals(null, result.cancellations().get(0).decidedAt());
        assertEquals(WaiterCancellationStatus.REQUESTED, result.cancellations().get(0).status());
        assertEquals(WaiterCancellationStatus.REJECTED, other.cancellations().get(0).status());
        Assertions.assertNotNull(other.cancellations().get(0).decidedAt());
    }

    // ---- reasons -------------------------------------------------------------------------------------------------------

    @Test
    void noReasonOneCodeOneTextOrBothFitTheWaitersShape() {
        var none = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null), List.of())).cancellations().get(0);
        assertTrue(none.legacy().isPresent());
        assertEquals(null, none.legacy().get().reasonCode());
        assertEquals(null, none.legacy().get().reasonText());

        var code = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null),
                List.of(reason("CHANGED_MIND", null, "GUEST", null)))).cancellations().get(0);
        assertEquals("CHANGED_MIND", code.legacy().get().reasonCode());
        assertEquals(null, code.legacy().get().reasonText());

        var both = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null),
                List.of(reason("OTHER", null, "GUEST", null), reason(null, "left early", "GUEST", null)))).cancellations()
                .get(0);
        assertEquals("OTHER", both.legacy().get().reasonCode());
        assertEquals("left early", both.legacy().get().reasonText());
    }

    @Test
    void severalCodesDoNotFitAndNobodyPicksOne() {
        var many = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null),
                List.of(reason("CHANGED_MIND", null, "GUEST", null), reason("WAIT_TOO_LONG", null, "GUEST", null),
                        reason(null, "tired of waiting", "GUEST", null)))).cancellations().get(0);

        assertTrue(many.legacy().isEmpty(), "no first, no last, no summary");
        assertEquals(3, many.reasons().size(), "all of them are still there");
        assertEquals("CHANGED_MIND", many.reasons().get(0).code());
        assertEquals("WAIT_TOO_LONG", many.reasons().get(1).code());
        assertEquals("tired of waiting", many.reasons().get(2).text());
    }

    @Test
    void everyReasonKeepsItsAuthor() {
        var staff = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null),
                List.of(reason("OTHER", null, "STAFF", "ADMIN")))).cancellations().get(0).reasons().get(0).author();
        var kitchen = WaiterMapper.cancellations(one(requestedBy("SERVICE", "KITCHEN", "KITCHEN"),
                List.of(reason("ITEM_UNAVAILABLE", null, "SERVICE", "KITCHEN")))).cancellations().get(0).reasons().get(0)
                .author();
        var guest = WaiterMapper.cancellations(one(requestedBy("GUEST", "g", null),
                List.of(reason("OTHER", null, "GUEST", "ignored")))).cancellations().get(0).reasons().get(0).author();

        assertEquals("STAFF", staff.kind());
        assertEquals("ADMIN", staff.role());
        assertEquals("SERVICE", kitchen.kind());
        assertEquals("KITCHEN", kitchen.role());
        assertEquals("GUEST", guest.kind());
        assertEquals(null, guest.role());
    }

    @Test
    void theCancelledLinesAndUnitsAreKeptAsTheyAre() {
        UUID line = UUID.randomUUID();
        var data = new CancellationData(requestedBy("GUEST", "g", null), List.of(itemRow(line, 3)), List.of());

        var result = WaiterMapper.cancellations(List.of(data)).cancellations().get(0);

        assertEquals(1, result.items().size());
        assertEquals(line, result.items().get(0).orderItemId());
        assertEquals(3, result.items().get(0).quantity());
    }
}
