package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Deny-by-default HTTP security. Guests and staff authenticate only through explicit Authorization headers,
 * verified by their respective ports. Neither filter reads cookies; HTTP Basic and form login are disabled,
 * and STATELESS prevents an existing HTTP session from supplying an authenticated security context.
 * Browsers do not automatically attach these Bearer/Staff credentials to forged cross-site requests.
 * HeaderAuthenticationSecurityTests verifies rejection of cookie, session and Basic credentials on mutations.
 * CSRF protection must be integrated before adding any browser-automatic authentication (SECURITY.md 3).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, QrSessionPort qrSessions, AuthContextPort authContext,
                                    ServiceIdentityPort serviceIdentity, ApiErrorWriter errors) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // OpenAPI endpoints exist only when SWAGGER_ENABLED=true (off by default); otherwise 404.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/carts/**").hasAuthority(GuestAuthentication.ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority(GuestAuthentication.ROLE)
                        // Reading one order: its own guest or staff; who exactly may see which order is decided
                        // (with tenant and ownership checks) in OrderQueryService, not only here.
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*", "/api/v1/orders/*/history")
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // Changing a status: authenticated staff only; which role may set which status is decided
                        // (with tenant checks) in OrderFulfillmentService. Never open to a guest or a service.
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/fulfillment-status")
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Cancelling an order: its own guest, or staff; the role (WAITER, ADMIN), tenant and ownership
                        // checks are made in OrderCancellationService. Never open to a service credential.
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/cancellations")
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // Reading the cancellations of an order: its own guest or staff (role checked in the service).
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/*/cancellations")
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // A waiter completes a missing reason: staff only (WAITER is checked in the service).
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/cancellations/*/reason")
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Cancellation report: authenticated staff only; ADMIN is required (and the tenant taken from the
                        // verified identity) in CancellationReportService.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/order-cancellations")
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Internal events: only the named calling service, never a guest or staff credential.
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen-cancellation-request")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen-cancellation")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/payment")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.PAYMENT))
                        // Table-session closure (ADR-QR-001): only the QR Service, which owns tables and their sessions.
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/table-sessions/*/closure-preparations")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/table-sessions/*/closure-preparations/*")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/table-sessions/*/order-state")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/internal/table-sessions/*/closure-preparations/*/confirmation")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .anyRequest().denyAll())
                .addFilterBefore(new GuestSessionAuthenticationFilter(qrSessions, errors),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new StaffAuthenticationFilter(authContext, errors),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new ServiceAuthenticationFilter(serviceIdentity, errors),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                errors.write(response, ErrorCode.UNAUTHENTICATED, "A valid guest session or staff credential is required"))
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(response, ErrorCode.ACCESS_DENIED, "Access to this resource is denied")))
                .build();
    }
}
