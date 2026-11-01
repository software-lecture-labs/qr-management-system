package com.restofy.waiter.application;

import com.restofy.waiter.application.service.CallFilter;
import com.restofy.waiter.application.service.CallMessage;
import com.restofy.waiter.application.service.CallSort;
import com.restofy.waiter.application.service.IdempotencyKeys;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import com.restofy.waiter.infrastructure.client.notification.NotificationAckDto;
import com.restofy.waiter.infrastructure.client.notification.ServiceCallEventDto;
import com.restofy.waiter.infrastructure.client.qr.QrGuestContextDto;
import com.restofy.waiter.infrastructure.config.NotificationDispatcherProperties;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRulesUnitTests {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    private static ServiceRequest openCall() {
        return ServiceRequest.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ServiceRequestType.BILL, "Hesap", NOW);
    }

    // ----- message -----

    @Test
    void messageIsOptionalTrimmedAndBounded() {
        assertNull(CallMessage.normalize(null));
        assertEquals("Su lütfen", CallMessage.normalize("  Su lütfen \t"));
        assertEquals(300, CallMessage.normalize("a".repeat(300)).length());
        assertEquals(300, CallMessage.normalize("😀".repeat(300)).codePointCount(0, 600),
                "Characters, not UTF-16 units, are counted");
        assertThrows(InvalidRequestException.class, () -> CallMessage.normalize("a".repeat(301)));
        assertThrows(InvalidRequestException.class, () -> CallMessage.normalize("😀".repeat(301)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\u00a0\u2003", "\u200b\u200d", "two\nlines", "carriage\rreturn", "nul\u0000", "bell\u0007",
            "del\u007f", "c1\u0085"})
    void blankOrControlTextIsRejected(String text) {
        assertThrows(InvalidRequestException.class, () -> CallMessage.normalize(text));
    }

    // ----- idempotency keys -----

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "a1b2c3d4-e5f6", "key:2026.10.16_01"})
    void validKeysAreAccepted(String key) {
        assertEquals(key, IdempotencyKeys.require(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "has space", "slash/inside", "ünicode-key", "new\nline"})
    void invalidKeysAreRejected(String key) {
        assertThrows(InvalidRequestException.class, () -> IdempotencyKeys.require(key));
        assertThrows(InvalidRequestException.class, () -> IdempotencyKeys.require(null));
    }

    @Test
    void keyLengthLimitsAreInclusive() {
        assertEquals(120, IdempotencyKeys.require("A".repeat(120)).length());
        assertThrows(InvalidRequestException.class, () -> IdempotencyKeys.require("a".repeat(121)));
        assertEquals(8, IdempotencyKeys.require("a".repeat(8)).length());
        assertThrows(InvalidRequestException.class, () -> IdempotencyKeys.require("a".repeat(7)));
    }

    @Test
    void digestsDistinguishEveryPartAndIgnoreNothing() {
        String base = IdempotencyKeys.digest("RESOLVE", "call", 3L);
        assertEquals(64, base.length());
        assertEquals(base, IdempotencyKeys.digest("RESOLVE", "call", 3L));
        assertNotEquals(base, IdempotencyKeys.digest("CLAIM", "call", 3L));
        assertNotEquals(base, IdempotencyKeys.digest("RESOLVE", "other", 3L));
        assertNotEquals(base, IdempotencyKeys.digest("RESOLVE", "call", 4L));
        // The separator keeps ("ab","c") and ("a","bc") apart.
        assertNotEquals(IdempotencyKeys.digest("ab", "c"), IdempotencyKeys.digest("a", "bc"));
        assertNotEquals(IdempotencyKeys.digest("a", null), IdempotencyKeys.digest("a", "x"));
    }

    // ----- listing criteria -----

    @Test
    void filterDefaultsToNoRestrictionsOldestFirst() {
        CallFilter filter = CallFilter.of(null, null, null, null, null, null, null);
        assertTrue(filter.types().isEmpty());
        assertTrue(filter.statuses().isEmpty());
        assertEquals(CallSort.CREATED_ASC, filter.sort());
        assertTrue(CallFilter.of(List.of(), List.of(), null, null, null, null, CallSort.CREATED_DESC).types().isEmpty());
    }

    @Test
    void filterDeduplicatesRepeatedValuesAndKeepsTheSelection() {
        CallFilter filter = CallFilter.of(List.of(ServiceRequestType.BILL, ServiceRequestType.BILL, ServiceRequestType.HELP),
                List.of(ServiceRequestStatus.OPEN), null, null, null, null, CallSort.CREATED_DESC);
        assertEquals(Set.of(ServiceRequestType.BILL, ServiceRequestType.HELP), filter.types());
        assertEquals(Set.of(ServiceRequestStatus.OPEN), filter.statuses());
        assertEquals(CallSort.CREATED_DESC, filter.sort());
    }

    @Test
    void filterRejectsTooManyValuesAndEmptyOrInvertedWindows() {
        List<ServiceRequestType> many = Collections.nCopies(11, ServiceRequestType.BILL);
        assertThrows(InvalidRequestException.class, () -> CallFilter.of(many, null, null, null, null, null, null));
        assertThrows(InvalidRequestException.class, () -> CallFilter.of(null, Collections.nCopies(11, ServiceRequestStatus.OPEN),
                null, null, null, null, null));
        assertThrows(InvalidRequestException.class, () -> CallFilter.of(null, null, null, null, NOW, NOW, null));
        assertThrows(InvalidRequestException.class, () -> CallFilter.of(null, null, null, null, NOW.plusSeconds(1), NOW, null));
        CallFilter.of(null, null, null, null, NOW, NOW.plusSeconds(1), null);
        CallFilter.of(null, null, null, null, NOW, null, null);
        CallFilter.of(null, null, null, null, null, NOW, null);
    }

    @Test
    void sortNamesMapToAFixedColumnWithAnIdTieBreaker() {
        assertEquals("createdAt: ASC,id: ASC", CallSort.CREATED_ASC.toSort().toString());
        assertEquals("createdAt: DESC,id: ASC", CallSort.CREATED_DESC.toSort().toString());
    }

    // ----- call transitions -----

    @Test
    void aNewCallIsOpenAndUnowned() {
        ServiceRequest call = openCall();
        assertEquals(ServiceRequestStatus.OPEN, call.getStatus());
        assertNull(call.getClaimedByUserId());
        assertNull(call.getResolvedByUserId());
        assertNull(call.getVersion(), "The version is assigned by persistence");
        assertEquals(NOW, call.getCreatedAt());
    }

    @Test
    void claimThenResolveRecordsBothActors() {
        ServiceRequest call = openCall();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        call.claim(a, NOW.plusSeconds(1));
        assertEquals(ServiceRequestStatus.CLAIMED, call.getStatus());
        assertEquals(a, call.getClaimedByUserId());
        call.resolve(b, NOW.plusSeconds(2));
        assertEquals(ServiceRequestStatus.RESOLVED, call.getStatus());
        assertEquals(a, call.getClaimedByUserId());
        assertEquals(b, call.getResolvedByUserId());
        assertEquals(NOW.plusSeconds(2), call.getResolvedAt());
        assertEquals(NOW.plusSeconds(2), call.getUpdatedAt());
    }

    @Test
    void anOpenCallCanBeResolvedWithoutClaiming() {
        ServiceRequest call = openCall();
        call.resolve(UUID.randomUUID(), NOW);
        assertEquals(ServiceRequestStatus.RESOLVED, call.getStatus());
        assertNull(call.getClaimedByUserId());
    }

    @Test
    void invalidTransitionsAreConflictsWithStableCodes() {
        ServiceRequest claimed = openCall();
        UUID first = UUID.randomUUID();
        claimed.claim(first, NOW);
        assertEquals("CALL_ALREADY_CLAIMED", assertThrows(CallConflictException.class, () -> claimed.claim(UUID.randomUUID(), NOW)).code());
        assertEquals(first, claimed.getClaimedByUserId(), "A claimer is never replaced");

        ServiceRequest resolved = openCall();
        resolved.resolve(first, NOW);
        assertEquals("CALL_ALREADY_RESOLVED", assertThrows(CallConflictException.class, () -> resolved.resolve(UUID.randomUUID(), NOW))
                .code());
        assertEquals("CALL_ALREADY_RESOLVED", assertThrows(CallConflictException.class, () -> resolved.claim(UUID.randomUUID(), NOW))
                .code());
        assertEquals(first, resolved.getResolvedByUserId(), "A resolver is never replaced");

        ServiceRequest cancelled = openCall();
        ReflectionTestUtils.setField(cancelled, "status", ServiceRequestStatus.CANCELLED);
        assertEquals("INVALID_TRANSITION", assertThrows(CallConflictException.class, () -> cancelled.claim(first, NOW)).code());
        assertEquals("INVALID_TRANSITION", assertThrows(CallConflictException.class, () -> cancelled.resolve(first, NOW)).code());
    }

    // ----- event envelope -----

    @Test
    void theEventEnvelopeCarriesTheSnapshotOfTheCall() {
        ServiceRequest call = openCall();
        UUID waiter = UUID.randomUUID();
        call.resolve(waiter, NOW.plusSeconds(5));
        ReflectionTestUtils.setField(call, "version", 4L);
        UUID eventId = UUID.randomUUID();

        ServiceCallEventDto event = ServiceCallEventDto.of(eventId, CallEventType.RESOLVED, NOW.plusSeconds(5), call);

        assertEquals(eventId, event.eventId());
        assertEquals(ServiceCallEventDto.SCHEMA_VERSION, event.schemaVersion());
        assertEquals("SERVICE_CALL_RESOLVED", event.eventType());
        assertEquals(call.getRestaurantId(), event.restaurantId());
        assertEquals(call.getId(), event.call().id());
        assertEquals(call.getTableId(), event.call().tableId());
        assertEquals(call.getTableSessionId(), event.call().tableSessionId());
        assertEquals(ServiceRequestStatus.RESOLVED, event.call().status());
        assertEquals(4L, event.call().version());
        assertEquals(waiter, event.call().resolvedByUserId());
        assertEquals(NOW.plusSeconds(5), event.call().resolvedAt());
    }

    @Test
    void everyEventTypeHasADistinctWireName() {
        assertEquals(Set.of("SERVICE_CALL_CREATED", "SERVICE_CALL_CLAIMED", "SERVICE_CALL_RESOLVED", "SERVICE_CALL_CANCELLED"),
                EnumSet.allOf(CallEventType.class).stream().map(CallEventType::wireName).collect(Collectors.toSet()));
    }

    @Test
    void acknowledgementsNeedAnEventAndAnOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationAckDto(null, NotificationAckDto.Outcome.APPLIED));
        assertThrows(IllegalArgumentException.class, () -> new NotificationAckDto(UUID.randomUUID(), null));
        assertEquals(3, NotificationAckDto.Outcome.values().length);
    }

    // ----- retry policy -----

    @Test
    void backoffDoublesFromTheInitialDelayAndStopsAtTheMaximum() {
        NotificationDispatcherProperties policy = new NotificationDispatcherProperties(true, 2000, 20, 120, 24, 5, 300);
        assertEquals(Duration.ofSeconds(5), policy.backoffAfter(1));
        assertEquals(Duration.ofSeconds(10), policy.backoffAfter(2));
        assertEquals(Duration.ofSeconds(20), policy.backoffAfter(3));
        assertEquals(Duration.ofSeconds(160), policy.backoffAfter(6));
        assertEquals(Duration.ofSeconds(300), policy.backoffAfter(7));
        assertEquals(Duration.ofSeconds(300), policy.backoffAfter(24));
        assertEquals(Duration.ofSeconds(300), policy.backoffAfter(Integer.MAX_VALUE));
        assertEquals(Duration.ofSeconds(120), policy.lease());
    }

    @Test
    void theTotalRetryWindowIsBoundedByTheAttemptLimit() {
        NotificationDispatcherProperties policy = new NotificationDispatcherProperties(true, 2000, 20, 120, 24, 5, 300);
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < policy.maxAttempts(); attempt++) total = total.plus(policy.backoffAfter(attempt));
        assertTrue(total.compareTo(Duration.ofHours(2)) < 0, "About an hour and a half, not unbounded: " + total);
    }

    @ParameterizedTest
    @CsvSource({"0,20,120,24,5,300", "2000,0,120,24,5,300", "2000,201,120,24,5,300", "2000,20,0,24,5,300",
            "2000,20,120,0,5,300", "2000,20,120,24,0,300", "2000,20,120,24,10,5"})
    void invalidPoliciesStopStartup(long interval, int batch, long lease, int attempts, long initial, long max) {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationDispatcherProperties(true, interval, batch, lease, attempts, initial, max));
    }

    // ----- identities and addresses -----

    @Test
    void aGuestPrincipalNeedsEveryReferenceAndTheServiceBoundaryRequiresOne() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new AuthenticatedGuest(null, id, id, id));
        assertThrows(NullPointerException.class, () -> new AuthenticatedGuest(id, null, id, id));
        assertThrows(NullPointerException.class, () -> new AuthenticatedGuest(id, id, null, id));
        assertThrows(NullPointerException.class, () -> new AuthenticatedGuest(id, id, id, null));
        AuthenticatedGuest guest = new AuthenticatedGuest(id, id, id, id);
        assertSame(guest, AuthenticatedGuest.require(guest));
        assertThrows(AccessDeniedException.class, () -> AuthenticatedGuest.require(null));
    }

    @ParameterizedTest
    @CsvSource({"OPEN,ACTIVE,true", "OPEN,ENDED,false", "OPEN,EXPIRED,false", "CLOSING,ACTIVE,false", "CLOSED,ACTIVE,false",
            "EXPIRED,ACTIVE,false"})
    void onlyAnOpenSessionWithAnActiveGuestMayRaiseCalls(QrGuestContextDto.TableSessionState table,
                                                         QrGuestContextDto.GuestSessionState guest, boolean active) {
        UUID id = UUID.randomUUID();
        QrGuestContextDto context = new QrGuestContextDto(id, id, new QrGuestContextDto.TableSession(id, table),
                new QrGuestContextDto.GuestSession(id, guest));
        assertEquals(active, context.isActive());
    }

    @Test
    void anIncompleteQrAnswerIsAContractViolation() {
        UUID id = UUID.randomUUID();
        var open = new QrGuestContextDto.TableSession(id, QrGuestContextDto.TableSessionState.OPEN);
        var active = new QrGuestContextDto.GuestSession(id, QrGuestContextDto.GuestSessionState.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto(null, id, open, active));
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto(id, null, open, active));
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto(id, id, null, active));
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto(id, id, open, null));
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto.TableSession(null,
                QrGuestContextDto.TableSessionState.OPEN));
        assertThrows(IllegalArgumentException.class, () -> new QrGuestContextDto.GuestSession(id, null));
        assertFalse(open.status() == null);
    }

    @Test
    void notificationAddressMustBeAnHttpBaseUrlWithoutCredentials() {
        URI ok = URI.create("http://service:8080");
        new ServiceUrlProperties(ok, ok, ok);
        assertThrows(IllegalArgumentException.class, () -> new ServiceUrlProperties(ok, ok, null));
        assertThrows(IllegalArgumentException.class, () -> new ServiceUrlProperties(ok, ok, URI.create("ftp://service")));
        assertThrows(IllegalArgumentException.class, () -> new ServiceUrlProperties(ok, ok, URI.create("http://user:pw@service")));
    }
}
