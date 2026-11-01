package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.SessionClosure.Staff;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** Closed until Waiter service identity AND an active staff delegation are verified.
 * Body restaurantId/requestedByUserId and arbitrary headers are expectations, never identity proof.
 * A real adapter reads locally verified request context; it must not perform network I/O under QR locks.
 */
@Component
public class WaiterLifecycleAccess {
    public Staff requireStaff() {
        throw new AuthenticationCredentialsNotFoundException("Verified Waiter and staff delegation are unavailable.");
    }
}
