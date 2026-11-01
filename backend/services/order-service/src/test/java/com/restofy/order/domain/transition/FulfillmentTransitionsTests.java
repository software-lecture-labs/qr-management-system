package com.restofy.order.domain.transition;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.enums.StaffRole;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.restofy.order.domain.enums.FulfillmentStatus.CANCELLED;
import static com.restofy.order.domain.enums.FulfillmentStatus.COMPLETED;
import static com.restofy.order.domain.enums.FulfillmentStatus.PREPARING;
import static com.restofy.order.domain.enums.FulfillmentStatus.READY;
import static com.restofy.order.domain.enums.FulfillmentStatus.RECEIVED;
import static com.restofy.order.domain.enums.FulfillmentStatus.SERVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FulfillmentTransitionsTests {

    @Test
    void theLifecycleIsOneStrictLineAndExactlyFourStepsExist() {
        int steps = 0;
        for (FulfillmentStatus from : FulfillmentStatus.values()) {
            for (FulfillmentStatus to : FulfillmentStatus.values()) {
                if (FulfillmentTransitions.isNextStep(from, to)) {
                    steps++;
                }
            }
        }
        assertEquals(4, steps);
        assertTrue(FulfillmentTransitions.isNextStep(RECEIVED, PREPARING));
        assertTrue(FulfillmentTransitions.isNextStep(PREPARING, READY));
        assertTrue(FulfillmentTransitions.isNextStep(READY, SERVED));
        assertTrue(FulfillmentTransitions.isNextStep(SERVED, COMPLETED));
    }

    @Test
    void nothingSkipsABackwardsMoveOrLeavesAFinalStatus() {
        assertFalse(FulfillmentTransitions.isNextStep(RECEIVED, READY));
        assertFalse(FulfillmentTransitions.isNextStep(READY, COMPLETED));
        assertFalse(FulfillmentTransitions.isNextStep(READY, PREPARING));
        assertFalse(FulfillmentTransitions.isNextStep(PREPARING, PREPARING));
        assertTrue(FulfillmentTransitions.next(COMPLETED).isEmpty());
        assertTrue(FulfillmentTransitions.next(CANCELLED).isEmpty());
        for (FulfillmentStatus from : FulfillmentStatus.values()) {
            assertFalse(FulfillmentTransitions.isNextStep(from, CANCELLED), "cancelling is not a status step");
            assertFalse(FulfillmentTransitions.isNextStep(from, RECEIVED), "nothing leads back to RECEIVED");
        }
    }

    @Test
    void lateOrRepeatedStatusesAreRecognisedAsAtOrBehind() {
        assertTrue(FulfillmentTransitions.isAtOrBehind(READY, READY));
        assertTrue(FulfillmentTransitions.isAtOrBehind(READY, PREPARING));
        assertTrue(FulfillmentTransitions.isAtOrBehind(COMPLETED, READY));
        assertFalse(FulfillmentTransitions.isAtOrBehind(PREPARING, READY));
        assertFalse(FulfillmentTransitions.isAtOrBehind(RECEIVED, READY));
        assertFalse(FulfillmentTransitions.isAtOrBehind(CANCELLED, PREPARING), "CANCELLED is off the line");
        assertFalse(FulfillmentTransitions.isAtOrBehind(READY, CANCELLED));
    }

    @Test
    void staffRolesMayOnlySetTheirOwnStatuses() {
        assertEquals(Set.of(PREPARING, READY), settableBy(StaffRole.KITCHEN));
        assertEquals(Set.of(SERVED, COMPLETED), settableBy(StaffRole.WAITER));
        assertEquals(Set.of(SERVED, COMPLETED), settableBy(StaffRole.ADMIN));
        for (StaffRole role : StaffRole.values()) {
            assertFalse(FulfillmentTransitions.mayStaffSet(role, CANCELLED));
            assertFalse(FulfillmentTransitions.mayStaffSet(role, RECEIVED));
        }
    }

    @Test
    void onlyTheKitchenServiceMayReportPreparationAndPaymentNeverMovesFulfillment() {
        for (FulfillmentStatus status : FulfillmentStatus.values()) {
            boolean kitchen = status == PREPARING || status == READY;
            assertEquals(kitchen, FulfillmentTransitions.mayServiceSet(ServiceName.KITCHEN, status), status.name());
            assertFalse(FulfillmentTransitions.mayServiceSet(ServiceName.PAYMENT, status), status.name());
        }
    }

    private static Set<FulfillmentStatus> settableBy(StaffRole role) {
        Set<FulfillmentStatus> result = EnumSet.noneOf(FulfillmentStatus.class);
        for (FulfillmentStatus status : FulfillmentStatus.values()) {
            if (FulfillmentTransitions.mayStaffSet(role, status)) {
                result.add(status);
            }
        }
        return result;
    }
}
