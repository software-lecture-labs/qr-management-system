package com.restofy.kitchen.infrastructure.security;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Deny by default. Kitchen endpoints need an authenticated KITCHEN or ADMIN principal; everything else is denied,
 * except the anonymous health endpoint that probes and pre-push rely on (no other actuator endpoint is open).
 * No authentication mechanism is wired because the Auth/service-identity contract is open (D-09): until it exists
 * all requests get 401. Form login, HTTP basic and the generated default user are intentionally absent.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
	private static final String KITCHEN_PATH = "/api/v1/kitchen/**";

	@Bean
	SecurityFilterChain kitchenSecurityFilterChain(HttpSecurity http) {
		http
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.authorizeHttpRequests(a -> a
						// Only the health endpoint (and its liveness/readiness groups) is anonymous, as in the other
						// services' default chain; details stay hidden (management.endpoint.health.show-details=never).
						.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
						// Reads: KITCHEN and ADMIN. Every other method (state changes) is KITCHEN only; ADMIN never writes.
						.requestMatchers(HttpMethod.GET, KITCHEN_PATH)
						.hasAnyRole(StaffRole.KITCHEN.name(), StaffRole.ADMIN.name())
						.requestMatchers(HttpMethod.HEAD, KITCHEN_PATH)
						.hasAnyRole(StaffRole.KITCHEN.name(), StaffRole.ADMIN.name())
						.requestMatchers(KITCHEN_PATH).hasRole(StaffRole.KITCHEN.name())
						.anyRequest().denyAll())
				.headers(Customizer.withDefaults());
		return http.build();
	}

	/** An empty user store: prevents Spring Boot from creating a generated default user/password. */
	@Bean
	UserDetailsService noUsers() {
		return new InMemoryUserDetailsManager();
	}
}
