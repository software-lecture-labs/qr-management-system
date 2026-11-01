package com.restofy.order.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole cart state machine, every pair of statuses. */
class CartStatusTests {

    private static final Set<String> ALLOWED = Set.of("ACTIVE>CHECKED_OUT", "ACTIVE>EXPIRED");

    @Test
    void onlyActiveCanLeaveItsStatusAndOnlyToCheckedOutOrExpired() {
        for (CartStatus from : CartStatus.values()) {
            for (CartStatus to : CartStatus.values()) {
                boolean expected = ALLOWED.contains(from + ">" + to);
                assertEquals(expected, from.canTransitionTo(to), from + " > " + to);
            }
        }
    }

    @Test
    void checkedOutAndExpiredAreFinal() {
        for (CartStatus finalStatus : new CartStatus[]{CartStatus.CHECKED_OUT, CartStatus.EXPIRED}) {
            for (CartStatus target : CartStatus.values()) {
                assertFalse(finalStatus.canTransitionTo(target), finalStatus + " must be final, reached " + target);
            }
        }
    }

    @Test
    void aCartCanNeverReturnToActiveOrStayInPlace() {
        for (CartStatus from : CartStatus.values()) {
            assertFalse(from.canTransitionTo(CartStatus.ACTIVE), from + " > ACTIVE");
            assertFalse(from.canTransitionTo(from), from + " > itself");
        }
    }

    @Test
    void onlyActiveIsModifiable() {
        assertTrue(CartStatus.ACTIVE.isModifiable());
        assertFalse(CartStatus.CHECKED_OUT.isModifiable());
        assertFalse(CartStatus.EXPIRED.isModifiable());
    }
}
