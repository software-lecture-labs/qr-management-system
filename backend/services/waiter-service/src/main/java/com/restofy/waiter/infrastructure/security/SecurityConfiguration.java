package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.infrastructure.client.qr.QrClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Deny-by-default filter chain. Only the health probes are open; everything else needs a verified identity.
 *
 * <ul>
 *   <li>Staff endpoints under {@code /api/v1/waiters/**} need the WAITER role. No staff authentication
 *       mechanism is configured because the Auth contract is not approved: no JWT, cookie or trusted-header
 *       model is invented here, so until Auth is wired in every staff request answers 401.</li>
 *   <li>{@code POST /api/v1/waiters/calls} is the single customer-facing endpoint. It needs the GUEST role,
 *       which only {@link GuestSessionAuthenticationFilter} grants, after QR verified the guest credential.</li>
 *   <li>CSRF protection stays on for every mutation except the guest call. Only a header credential, which a
 *       browser never attaches on its own, can authenticate that request, and it is refused for anyone else, so
 *       a forged cross-site request has no ambient authority to abuse (a request without the credential is a
 *       401, not a CSRF failure). Staff mutations therefore stay blocked until Auth decides between a cookie
 *       plus CSRF token and a header credential.</li>
 * </ul>
 *
 * Authorization is repeated at the service boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain waiterSecurityFilterChain(HttpSecurity http, QrClient qr,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.ignoringRequestMatchers(GuestSessionAuthenticationFilter::isGuestCallRequest))
                .addFilterBefore(new GuestSessionAuthenticationFilter(qr, resolver), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, GuestSessionAuthenticationFilter.CALLS_PATH).hasRole("GUEST")
                        .requestMatchers("/api/v1/waiters", "/api/v1/waiters/**").hasRole("WAITER")
                        .anyRequest().denyAll())
                // Errors go through the controller advice so security and API errors share one format.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                resolver.resolveException(request, response, null, error))
                        .accessDeniedHandler((request, response, error) ->
                                resolver.resolveException(request, response, null, error)));
        return http.build();
    }

    /** Without this bean Boot would create a user with a generated password that is printed to the log. */
    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("The waiter service has no local users");
        };
    }
}
