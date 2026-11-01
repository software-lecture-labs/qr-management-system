package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract with the Auth Service for STAFF (ADMIN, WAITER, KITCHEN) authentication. Guests are authenticated through
 * the QR Service ({@link QrSessionPort}) and services through {@code ServiceIdentityPort}; the three are separate on
 * purpose, so a guest session can never act as staff and a staff credential can never act as a service.
 *
 * <p>The credential is an opaque string taken from {@code Authorization: Staff <credential>}; the Order Service never
 * interprets it. Implementations must be fail-closed: an unknown, expired, revoked or tampered credential yields
 * {@link Optional#empty()}, and an unreachable Auth Service throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE}, never a successful result. The returned restaurant is the staff member's ACTIVE
 * restaurant context as verified by Auth.
 *
 * <p>The real adapter is the Auth Service team's to provide. Until then: a MOCK exists in the {@code dev} and
 * {@code test} profiles only, and every other profile gets a stand-in that rejects every call with 503.
 */
public interface AuthContextPort {

    Optional<StaffActor> resolveStaff(String credential);
}
