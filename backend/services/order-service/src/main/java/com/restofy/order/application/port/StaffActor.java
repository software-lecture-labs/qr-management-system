package com.restofy.order.application.port;

import com.restofy.order.domain.enums.StaffRole;

import java.util.Objects;
import java.util.UUID;

/**
 * A staff member as verified by the Auth Service: who they are, which restaurant they act for, and in which role.
 * Every field comes from {@link AuthContextPort}; none of it is ever read from request headers or bodies.
 */
public record StaffActor(UUID userId, UUID restaurantId, StaffRole role) {

    public StaffActor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(role, "role");
    }
}
