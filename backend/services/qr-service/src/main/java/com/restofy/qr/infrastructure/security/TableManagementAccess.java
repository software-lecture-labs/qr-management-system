package com.restofy.qr.infrastructure.security;

import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** Closed integration boundary until the Auth team supplies verified management/tenant context.
 *  No request ID, header, default Security user or role establishes restaurant membership.
 */
@Component
public class TableManagementAccess {
    public UUID requireRestaurantId() {
        throw new AuthenticationCredentialsNotFoundException("Verified restaurant management context is unavailable.");
    }
}
