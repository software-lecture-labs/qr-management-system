package com.restofy.kitchen.infrastructure.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authenticated staff identity. Only a component that has verified a credential may create it. No such component
 * exists in production yet (Auth contract is open), so every kitchen request is rejected with 401 (fail closed).
 */
public class KitchenAuthentication extends AbstractAuthenticationToken {

	private final KitchenPrincipal principal;

	public KitchenAuthentication(KitchenPrincipal principal, StaffRole role) {
		super(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		this.principal = principal;
		super.setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return "";
	}

	@Override
	public KitchenPrincipal getPrincipal() {
		return principal;
	}
}
