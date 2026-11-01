package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.StaffActor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** An authenticated staff member. The principal is the identity verified by the Auth Service, not client input. */
public class StaffAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE = "ROLE_STAFF";

    private final transient StaffActor actor;

    public StaffAuthentication(StaffActor actor) {
        super(AuthorityUtils.createAuthorityList(ROLE, ROLE + "_" + actor.role().name()));
        this.actor = actor;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StaffAuthentication && super.equals(other);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
