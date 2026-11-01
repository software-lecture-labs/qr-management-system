package com.restofy.qr.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class TableSecurityConfiguration {
    @Bean
    SecurityFilterChain qrSecurityFilterChain(HttpSecurity http) {
        // Keep CSRF and default security headers. No login or temporary auth mechanism is added.
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .anyRequest().denyAll());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeProblem(response, 401))
                .accessDeniedHandler((request, response, exception) -> writeProblem(response, 403)));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(status == 401
                ? "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Verified management context is required.\"}"
                : "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Table management access is unavailable.\"}");
    }
}
