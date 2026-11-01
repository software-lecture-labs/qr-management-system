package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.domain.enums.StaffRole;
import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;
import java.util.UUID;

/**
 * Verified staff context: who is calling, for which restaurant and with which role. This is the single
 * place the restaurant comes from; request parameters and headers never supply it.
 *
 * <p>NOT IMPLEMENTED: nothing in this service creates this principal yet. The Auth contract (token or
 * session format, issuer, how the restaurant context is bound) is not approved, so every protected
 * request is rejected until an authentication mechanism fills the SecurityContext with this type.
 */
public record AuthenticatedStaff(UUID staffUserId, UUID restaurantId, StaffRole role) {

    public AuthenticatedStaff {
        Objects.requireNonNull(staffUserId, "staffUserId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(role, "role");
    }

    /**
     * Service-boundary authorization used by every query: the caller must be a verified WAITER, and the
     * returned restaurant id is the only tenant the query may touch.
     */
    public static UUID requireWaiterRestaurant(AuthenticatedStaff staff) {
        if (staff == null || staff.role() != StaffRole.WAITER) {
            throw new AccessDeniedException("Waiter role is required");
        }
        return staff.restaurantId();
    }
}
