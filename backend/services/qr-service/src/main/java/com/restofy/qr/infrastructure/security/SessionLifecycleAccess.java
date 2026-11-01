package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.SessionClosure.Staff;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** Local verified Auth context only: current active WAITER/ADMIN membership for normal close/status/cleaned.
 * Never infer it from default Security users, browser headers, or guest tokens.
 * A future adapter must not perform remote I/O under a lifecycle transaction.
 */
@Component
public class SessionLifecycleAccess {
    public Staff requireStaff() {
        throw new AuthenticationCredentialsNotFoundException("Verified restaurant lifecycle permission is unavailable.");
    }
}
