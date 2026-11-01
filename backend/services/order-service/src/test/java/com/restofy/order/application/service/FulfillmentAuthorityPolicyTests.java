package com.restofy.order.application.service;

import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * K-05: outside dev/test the Kitchen Service is the only source of PREPARING and READY. A MockEnvironment is used because
 * the Maven build sets the active profile as a system property that a plain Environment would pick up.
 */
class FulfillmentAuthorityPolicyTests {

    private static FulfillmentAuthorityPolicy policyFor(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return new FulfillmentAuthorityPolicy(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void kitchenStaffMaySetPreparationStatusesOnlyInDevAndTest(String profile) {
        assertTrue(policyFor(profile).kitchenStaffMaySetPreparationStatuses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "qa"})
    void inEveryOtherProfileOnlyTheKitchenServiceMay(String profile) {
        assertFalse(policyFor(profile).kitchenStaffMaySetPreparationStatuses());
    }

    @Test
    void withoutAnyProfileItIsClosedToo() {
        assertFalse(policyFor().kitchenStaffMaySetPreparationStatuses());
    }

    @Test
    void theServiceRefusesKitchenStaffBeforeTouchingTheDatabaseWhereItIsClosed() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderStatusHistoryStore history = mock(OrderStatusHistoryStore.class);
        OutboxEventWriter outbox = mock(OutboxEventWriter.class);
        InboxStore inbox = mock(InboxStore.class);
        OrderFulfillmentService service = new OrderFulfillmentService(orders, history, outbox, inbox,
                Clock.systemUTC(), policyFor("prod"));
        StaffActor kitchen = new StaffActor(UUID.randomUUID(), UUID.randomUUID(), StaffRole.KITCHEN);

        for (String status : new String[]{"PREPARING", "READY"}) {
            UUID orderId = UUID.randomUUID();
            BusinessException refused = assertThrows(BusinessException.class,
                    () -> service.changeByStaff(kitchen, orderId, status, null));
            assertEquals(ErrorCode.ACCESS_DENIED, refused.code());
        }
        verifyNoInteractions(orders, history, outbox, inbox);
    }
}
