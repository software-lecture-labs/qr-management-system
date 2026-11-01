package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrGuestContextDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates the one customer-facing request, {@code POST /api/v1/waiters/calls}. The guest presents the
 * opaque credential it received from QR in {@link #HEADER}; QR verifies it, and the answer, not the request,
 * decides restaurant, table, table session and guest session. Nothing else in the request is trusted.
 *
 * <p>This is not a public endpoint: without a credential that QR accepts the request is a 401, and a known
 * credential whose session has ended is a 409. The credential is never logged or stored. It is a custom
 * header rather than a cookie on purpose, so a browser never attaches it on its own (no ambient authority).
 *
 * <p>The filter is created by the security configuration and is not a Spring bean, otherwise Boot would also
 * register it as a global servlet filter.
 */
final class GuestSessionAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Guest-Token";
    static final String CALLS_PATH = "/api/v1/waiters/calls";
    private static final int MAX_TOKEN_LENGTH = 512;

    private final QrClient qr;
    private final HandlerExceptionResolver errors;

    GuestSessionAuthenticationFilter(QrClient qr, HandlerExceptionResolver errors) {
        this.qr = qr;
        this.errors = errors;
    }

    static boolean isGuestCallRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && CALLS_PATH.equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isGuestCallRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        List<String> tokens = Collections.list(request.getHeaders(HEADER));
        if (tokens.isEmpty() || (existing != null && !(existing instanceof AnonymousAuthenticationToken))) {
            chain.doFilter(request, response);
            return;
        }
        if (tokens.size() != 1 || !wellFormed(tokens.getFirst())) {
            errors.resolveException(request, response, null, new BadCredentialsException("Invalid guest credential"));
            return;
        }
        Optional<QrGuestContextDto> context;
        try {
            context = qr.verifyGuest(tokens.getFirst());
        } catch (DependencyException failure) {
            errors.resolveException(request, response, null, failure);
            return;
        }
        if (context.isEmpty()) {
            errors.resolveException(request, response, null, new BadCredentialsException("Unknown guest credential"));
            return;
        }
        QrGuestContextDto verified = context.get();
        if (!verified.isActive()) {
            errors.resolveException(request, response, null, new CallConflictException("SESSION_NOT_ACTIVE",
                    "The table session or guest session is no longer active"));
            return;
        }
        AuthenticatedGuest guest = new AuthenticatedGuest(verified.restaurantId(), verified.tableId(),
                verified.tableSession().id(), verified.guestSession().id());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(guest, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
        SecurityContextHolder.setContext(securityContext);
        chain.doFilter(request, response);
    }

    /** Printable ASCII only and bounded, so a hostile header never reaches QR or a log. */
    private static boolean wellFormed(String token) {
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) return false;
        for (int index = 0; index < token.length(); index++) {
            char c = token.charAt(index);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }
}
