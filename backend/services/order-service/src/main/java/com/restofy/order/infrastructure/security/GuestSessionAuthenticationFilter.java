package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.QrSessionPort;
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
 * Authenticates a QR guest from {@code Authorization: Bearer <guest session token>} by asking the QR Service
 * (through {@link QrSessionPort}) who the token belongs to. Restaurant, table and guest ids are never taken from
 * the request. An invalid token is a 401; an unreachable QR Service is a 503 (fail closed). Tokens are never logged.
 */
@Slf4j
public class GuestSessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final QrSessionPort qrSessions;
    private final ApiErrorWriter errors;

    public GuestSessionAuthenticationFilter(QrSessionPort qrSessions, ApiErrorWriter errors) {
        this.qrSessions = qrSessions;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The Staff and Service schemes belong to their own filters; guests only ever use Bearer.
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return !request.getRequestURI().startsWith("/api/")
                || StaffAuthenticationFilter.isStaffScheme(authorization)
                || ServiceAuthenticationFilter.isServiceScheme(authorization);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            reject(response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.length() > SecurityLimits.MAX_CREDENTIAL_LENGTH) {
            reject(response);
            return;
        }
        Optional<GuestSessionContext> context;
        try {
            context = qrSessions.validateGuestSession(token);
        } catch (BusinessException unavailable) {
            errors.write(response, unavailable.code(), unavailable.getMessage());
            return;
        } catch (RuntimeException unexpected) {
            // Fail closed: if the session cannot be verified, nothing is allowed through.
            log.error("Guest session validation failed unexpectedly", unexpected);
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable");
            return;
        }
        if (context.isEmpty()) {
            reject(response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new GuestAuthentication(context.get()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        errors.write(response, ErrorCode.UNAUTHENTICATED, "A valid guest session is required");
    }
}
