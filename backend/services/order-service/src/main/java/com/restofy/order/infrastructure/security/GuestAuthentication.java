package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.GuestSessionContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** An authenticated QR guest. The principal is the context resolved by the QR Service, not client input. */
public class GuestAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE = "ROLE_GUEST";

    private final transient GuestSessionContext context;

    public GuestAuthentication(GuestSessionContext context) {
        super(AuthorityUtils.createAuthorityList(ROLE));
        this.context = context;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return context;
    }
}
