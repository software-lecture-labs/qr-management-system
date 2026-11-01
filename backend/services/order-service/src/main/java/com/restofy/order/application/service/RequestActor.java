package com.restofy.order.application.service;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.security.core.Authentication;

/** Who is calling: a QR guest or a staff member, each verified by its own port. */
public sealed interface RequestActor {

    record Guest(GuestSessionContext context) implements RequestActor {
    }

    record Staff(StaffActor actor) implements RequestActor {
    }

    /** Reads the verified identity from the security context; anything else is not an actor and is refused. */
    static RequestActor from(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof GuestSessionContext guest) {
            return new Guest(guest);
        }
        if (authentication != null && authentication.getPrincipal() instanceof StaffActor staff) {
            return new Staff(staff);
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED, "A verified guest session or staff identity is required");
    }
}
