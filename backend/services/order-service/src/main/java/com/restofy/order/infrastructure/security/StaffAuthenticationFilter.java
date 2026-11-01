package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates STAFF from {@code Authorization: Staff <credential>} through the {@link AuthContextPort}. This
 * scheme is deliberately different from the guest scheme ({@code Bearer}): a guest token is never offered to the Auth
 * Service and a staff credential is never offered to the QR Service. An unknown credential is a 401; an unreachable or
 * failing Auth Service is a 503 (fail closed). Credentials are never logged.
 */
@Slf4j
public class StaffAuthenticationFilter extends OncePerRequestFilter {

    static final String SCHEME = "Staff ";

    private final AuthContextPort authContext;
    private final ApiErrorWriter errors;

    public StaffAuthenticationFilter(AuthContextPort authContext, ApiErrorWriter errors) {
        this.authContext = authContext;
        this.errors = errors;
    }

    static boolean isStaffScheme(String header) {
        return header != null && header.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || !isStaffScheme(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION).substring(SCHEME.length()).trim();
        if (credential.isEmpty() || credential.length() > SecurityLimits.MAX_CREDENTIAL_LENGTH) {
            reject(response);
            return;
        }
        Optional<StaffActor> actor;
        try {
            actor = authContext.resolveStaff(credential);
        } catch (BusinessException unavailable) {
            errors.write(response, unavailable.code(), unavailable.getMessage());
            return;
        } catch (RuntimeException unexpected) {
            // Fail closed: if the staff identity cannot be verified, nothing is allowed through.
            log.error("Staff authentication failed unexpectedly", unexpected);
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable");
            return;
        }
        if (actor.isEmpty()) {
            reject(response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new StaffAuthentication(actor.get()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        errors.write(response, ErrorCode.UNAUTHENTICATED, "A valid staff credential is required");
    }
}
