package com.restofy.waiter.application;

import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.application.service.OrderFilter;
import com.restofy.waiter.application.service.PageParams;
import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.OrderSessionScope;
import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceUnitTests {

    @Test
    void pageParamsEnforceTheBounds() {
        assertEquals(40, new PageParams(2, 20).offset());
        assertEquals(Integer.MAX_VALUE * 100L, new PageParams(Integer.MAX_VALUE, 100).offset());
        new PageParams(0, 1);
        new PageParams(0, PageParams.MAX_SIZE);
        assertThrows(InvalidRequestException.class, () -> new PageParams(-1, 20));
        assertThrows(InvalidRequestException.class, () -> new PageParams(0, 0));
        assertThrows(InvalidRequestException.class, () -> new PageParams(0, PageParams.MAX_SIZE + 1));
    }

    @Test
    void pageResponseComputesTotalPagesFromTheWholeSet() {
        assertEquals(0, PageResponse.of(List.of(), 0, 20, 0).totalPages());
        assertEquals(1, PageResponse.of(List.of(1), 0, 20, 1).totalPages());
        assertEquals(2, PageResponse.of(List.of(1), 1, 20, 21).totalPages());
    }

    @Test
    void orderFilterDefaultsToTheLiveSessions() {
        assertEquals(OrderSessionScope.CURRENT, new OrderFilter(null, null, null).scope());
    }

    @Test
    void onlyAVerifiedWaiterGetsARestaurant() {
        UUID restaurant = UUID.randomUUID();
        assertEquals(restaurant, AuthenticatedStaff.requireWaiterRestaurant(
                new AuthenticatedStaff(UUID.randomUUID(), restaurant, StaffRole.WAITER)));
        assertThrows(AccessDeniedException.class, () -> AuthenticatedStaff.requireWaiterRestaurant(null));
        for (StaffRole role : List.of(StaffRole.ADMIN, StaffRole.KITCHEN)) {
            assertThrows(AccessDeniedException.class, () -> AuthenticatedStaff.requireWaiterRestaurant(
                    new AuthenticatedStaff(UUID.randomUUID(), restaurant, role)));
        }
        assertThrows(NullPointerException.class, () -> new AuthenticatedStaff(null, restaurant, StaffRole.WAITER));
        assertThrows(NullPointerException.class, () -> new AuthenticatedStaff(UUID.randomUUID(), null, StaffRole.WAITER));
        assertThrows(NullPointerException.class, () -> new AuthenticatedStaff(UUID.randomUUID(), restaurant, null));
    }

    @ParameterizedTest
    @CsvSource({
            // view, business, preparation, delivery, matches
            "ACTIVE,ACCEPTED,PREPARING,PENDING,true",
            "ACTIVE,PARTIALLY_CANCELLED,READY,SERVED,true",
            "ACTIVE,COMPLETED,READY,SERVED,false",
            "ACTIVE,CANCELLED,CANCELLED,PENDING,false",
            "READY_HISTORY,ACCEPTED,READY,PENDING,true",
            "READY_HISTORY,COMPLETED,READY,SERVED,true",
            "READY_HISTORY,ACCEPTED,PREPARING,PENDING,false",
            "READY_HISTORY,CANCELLED,READY,PENDING,false",
            "AWAITING_DELIVERY,ACCEPTED,READY,PENDING,true",
            "AWAITING_DELIVERY,ACCEPTED,READY,SERVED,false",
            "AWAITING_DELIVERY,ACCEPTED,WAITING,PENDING,false",
            "AWAITING_DELIVERY,CANCELLED,READY,PENDING,false",
            "CANCELLED,CANCELLED,CANCELLED,PENDING,true",
            "CANCELLED,PARTIALLY_CANCELLED,READY,PENDING,false"})
    void orderViewsHaveExplicitPredicates(OrderView view, OrderBusinessStatus business, PreparationStatus preparation,
                                          DeliveryStatus delivery, boolean expected) {
        assertEquals(expected, view.matches(business, preparation, delivery));
    }

    @Test
    void unknownKitchenProjectionIsNeverReady() {
        for (OrderView view : List.of(OrderView.READY_HISTORY, OrderView.AWAITING_DELIVERY)) {
            assertFalse(view.matches(OrderBusinessStatus.ACCEPTED, null, DeliveryStatus.PENDING));
        }
        assertTrue(OrderView.ACTIVE.matches(OrderBusinessStatus.ACCEPTED, null, DeliveryStatus.PENDING));
    }
}
