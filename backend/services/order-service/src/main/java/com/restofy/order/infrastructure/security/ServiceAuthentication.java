package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.enums.ServiceName;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** An authenticated calling service. It carries no user or restaurant authority, only its own service authority. */
public class ServiceAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE = "ROLE_SERVICE";

    private final transient ServiceActor actor;

    public ServiceAuthentication(ServiceActor actor) {
        super(AuthorityUtils.createAuthorityList(ROLE, authority(actor.name())));
        this.actor = actor;
        setAuthenticated(true);
    }

    /** The authority that opens exactly the internal endpoints of one service. */
    public static String authority(ServiceName name) {
        return ROLE + "_" + name.name();
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
        return other instanceof ServiceAuthentication && super.equals(other);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
