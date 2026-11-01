package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract for verifying a calling SERVICE (Kitchen, Payment) on the internal endpoints. It is separate from
 * {@link AuthContextPort} (staff) and {@link QrSessionPort} (guests) on purpose: a staff credential or guest token can
 * never act as a service, and a service credential can never act as a user.
 *
 * <p>The credential is an opaque string taken from {@code Authorization: Service <credential>}; the Order Service
 * never interprets it. Implementations must be fail-closed: an unknown, expired or tampered credential yields
 * {@link Optional#empty()}, and an unreachable verifier throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE}, never a successful result.
 *
 * <p><b>Target contract (decision K-02, to be finalised with the Auth Service team):</b> the credential is a short-lived,
 * signed service token carried over TLS. A real adapter must verify the signature against keys that can be rotated,
 * the issuer, the audience (this service), the validity period (with a small clock tolerance), and map the verified
 * subject to a {@link ServiceActor}; it must also bind the caller to the operations it may perform (the endpoint
 * authorities in {@code SecurityConfiguration} already do that per service) and consider token replay (for example a
 * token id). mTLS may be added later as an extra layer but is not required. This interface does not change when the
 * adapter does. No real issuer or verifier exists yet.
 *
 * <p>Until a real adapter exists a MOCK is available in the {@code dev} and {@code test} profiles only, and every other
 * profile gets a stand-in that rejects every call with 503, so the internal endpoints stay closed in production.
 */
public interface ServiceIdentityPort {

    Optional<ServiceActor> resolveService(String credential);
}
