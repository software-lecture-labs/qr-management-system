package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer-facing call endpoint. The guest authenticates with a credential that QR verifies; restaurant,
 * table, table session and guest session come only from QR's answer. QR is the local HTTP stub of the DRAFT
 * contract, so these tests do not prove behaviour against the real QR service.
 */
class GuestCallApiTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private FakeBackends.Table table;
    private FakeBackends.Guest guest;

    @BeforeEach
    void occupiedTableWithAGuest() {
        table = addTable("12", "OCCUPIED", SESSION);
        guest = addGuest(table);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNothingWritten() {
        assertEquals(0, count("service_requests"));
        assertEquals(0, count("service_call_events"));
        assertEquals(0, count("notification_deliveries"));
        assertEquals(0, count("idempotency_records"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITER", "HELP", "BILL"})
    void everyCallTypeIsBoundToTheTableSessionAndGuestThatQrVerified(String type) throws Exception {
        raiseCall(guest, newKey(), "{\"type\":\"" + type + "\",\"message\":\"  Su istiyoruz  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.message").value("Su istiyoruz"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                // The guest sees its own call only: no internal references, no staff identities.
                .andExpect(jsonPath("$.guestSessionId").doesNotExist())
                .andExpect(jsonPath("$.tableSessionId").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM service_requests");
        assertEquals(RESTAURANT, row.get("restaurant_id"));
        assertEquals(table.id(), row.get("table_id"));
        assertEquals(SESSION, row.get("table_session_id"));
        assertEquals(guest.guestSessionId(), row.get("guest_session_id"));
        assertEquals(type, row.get("request_type"));
        assertEquals("OPEN", row.get("status"));
        assertEquals("Su istiyoruz", row.get("message"));
        assertEquals(0L, row.get("version"));
        assertNull(row.get("claimed_by_user_id"));
        assertNull(row.get("resolved_by_user_id"));

        // Audit: who (the guest session), what and when; plus the delivery waiting for Notification.
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM service_call_events");
        assertEquals("CREATED", event.get("event_type"));
        assertEquals("GUEST", event.get("actor_type"));
        assertEquals(guest.guestSessionId(), event.get("actor_id"));
        assertEquals(0L, event.get("call_version"));
        assertEquals("OPEN", event.get("to_status"));
        assertNull(event.get("from_status"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
    }

    @Test
    void qrIsAskedToVerifyTheCredentialInTheBodyNeverInTheUrl() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        List<DownstreamStub.Request> verifications = STUB.requests("POST", "/api/v1/internal/guest-sessions/verify");
        assertEquals(1, verifications.size());
        DownstreamStub.Request verification = verifications.getFirst();
        assertTrue(verification.body().contains(guest.token()));
        assertTrue(verification.rawQuery() == null || verification.rawQuery().isEmpty());
        assertNull(verification.header("Authorization"), "No invented service credential");
        assertNull(verification.header("X-Guest-Token"), "The credential is forwarded in the body only");
    }

    @Test
    void theCredentialIsNeverStoredAnywhere() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        StringBuilder everything = new StringBuilder();
        for (String table : List.of("service_requests", "service_call_events", "notification_deliveries", "idempotency_records")) {
            everything.append(jdbc.queryForList("SELECT * FROM " + table).toString());
        }
        assertFalse(everything.toString().contains(guest.token()));
    }

    @Test
    void requestWithoutACredentialIsAuthenticationRequiredAndTouchesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertTrue(STUB.requests().isEmpty());
        assertNothingWritten();
    }

    @Test
    void forgedIdentityHeadersDoNotAuthenticate() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("Idempotency-Key", newKey())
                        .header("X-Restaurant-Id", RESTAURANT.toString()).header("X-Role", "GUEST")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isUnauthorized());
        assertNothingWritten();
    }

    @Test
    void credentialUnknownToQrIsRejectedWithoutSayingWhy() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", "never-issued-token")
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HELP\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has a space", "tab\tinside", "ünicode-token"})
    void malformedCredentialsNeverReachQr(String token) throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", token)
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HELP\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(STUB.requests().isEmpty());
        assertNothingWritten();
    }

    @Test
    void oversizedAndRepeatedCredentialsNeverReachQr() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", "x".repeat(513))
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HELP\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", guest.token(), guest.token())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HELP\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(STUB.requests().isEmpty());
        assertNothingWritten();
    }

    @Test
    void theCredentialAuthenticatesOnlyTheCallEndpointNotStaffEndpoints() throws Exception {
        for (String path : List.of("/api/v1/waiters/calls", "/api/v1/waiters/tables", "/api/v1/waiters/orders",
                "/api/v1/waiters/calls/" + UUID.randomUUID())) {
            mockMvc.perform(get(path).header("X-Guest-Token", guest.token())).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/waiters/calls/" + UUID.randomUUID() + "/resolve").header("X-Guest-Token", guest.token())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        assertTrue(STUB.requests().isEmpty(), "Only the call endpoint verifies a guest credential");
    }

    @ParameterizedTest
    @CsvSource({"CLOSED,ACTIVE", "EXPIRED,ACTIVE", "CLOSING,ACTIVE", "OPEN,ENDED", "OPEN,EXPIRED"})
    void endedClosingOrExpiredSessionsCannotRaiseCalls(String tableSession, String guestSession) throws Exception {
        backends.guests.put(guest.token(), guest.withStatuses(tableSession, guestSession));
        raiseCall(guest, "BILL")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_ACTIVE"));
        assertNothingWritten();
    }

    @Test
    void staffCannotUseTheGuestEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").with(asWaiter()).with(csrf().asHeader()).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // Even with a valid guest credential on top of a staff login, the staff identity wins and is refused.
        mockMvc.perform(post("/api/v1/waiters/calls").with(asWaiter()).with(csrf().asHeader()).header("X-Guest-Token", guest.token())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isForbidden());
        assertNothingWritten();
    }

    @Test
    void identityFieldsInTheBodyAreRejectedNotIgnored() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        for (String extra : List.of("\"restaurantId\":\"" + OTHER_RESTAURANT + "\"", "\"tableId\":\"" + someoneElse + "\"",
                "\"tableSessionId\":\"" + OTHER_SESSION + "\"", "\"guestSessionId\":\"" + someoneElse + "\"",
                "\"status\":\"RESOLVED\"", "\"role\":\"WAITER\"")) {
            raiseCall(guest, newKey(), "{\"type\":\"WAITER\"," + extra + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertNothingWritten();
    }

    @Test
    void queryParametersAndForgedHeadersCannotChangeTheTenantOrSession() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").param("restaurantId", OTHER_RESTAURANT.toString())
                        .header("X-Guest-Token", guest.token()).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isBadRequest());
        assertNothingWritten();

        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Restaurant-Id", OTHER_RESTAURANT.toString())
                        .header("X-Table-Session-Id", OTHER_SESSION.toString())
                        .header("X-Guest-Token", guest.token()).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isCreated());
        Map<String, Object> row = jdbc.queryForMap("SELECT restaurant_id, table_session_id FROM service_requests");
        assertEquals(RESTAURANT, row.get("restaurant_id"));
        assertEquals(SESSION, row.get("table_session_id"));
    }

    @Test
    void aGuestOfAnotherRestaurantCreatesCallsOnlyInItsOwnRestaurant() throws Exception {
        FakeBackends.Table foreign = addTable(OTHER_RESTAURANT, "1", "OCCUPIED", OTHER_SESSION);
        FakeBackends.Guest foreignGuest = addGuest(foreign);
        raiseCall(foreignGuest, "HELP").andExpect(status().isCreated());
        assertEquals(OTHER_RESTAURANT, jdbc.queryForObject("SELECT restaurant_id FROM service_requests", UUID.class));
        mockMvc.perform(get("/api/v1/waiters/calls").with(asWaiter()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsInvalidBodiesBeforeAnythingIsWritten() throws Exception {
        for (String body : List.of("{}", "{\"type\":null}", "{\"type\":\"COMPLAINT\"}", "{\"type\":\"waiter\"}",
                "{\"type\":7}", "{\"type\":\"WAITER\",\"message\":\"\"}", "{\"type\":\"WAITER\",\"message\":\"   \"}",
                "{\"type\":\"WAITER\",\"message\":\"" + "x".repeat(301) + "\"}",
                "{\"type\":\"WAITER\",\"message\":\"line one\\nline two\"}",
                "{\"type\":\"WAITER\",\"message\":\"bell\\u0007\"}", "{\"type\":\"WAITER\",\"message\":12}",
                "[]", "not json", "")) {
            var response = raiseCall(guest, newKey(), body).andReturn().getResponse();
            assertEquals(400, response.getStatus(), "body: " + body);
            assertEquals("INVALID_REQUEST", FakeBackends.read(response.getContentAsString()).get("code").asString(), "body: " + body);
        }
        assertNothingWritten();
    }

    @Test
    void messageAtTheLimitIsAcceptedCountingCharactersNotBytes() throws Exception {
        raiseCall(guest, newKey(), "{\"type\":\"HELP\",\"message\":\"" + "ç".repeat(300) + "\"}")
                .andExpect(status().isCreated());
        assertEquals(300, jdbc.queryForObject("SELECT char_length(message) FROM service_requests", Integer.class));
    }

    @Test
    void hostileMessageTextIsStoredAsPlainTextAndReturnedUnchanged() throws Exception {
        String hostile = "<script>alert(1)</script> '; DROP TABLE service_requests; --";
        raiseCall(guest, newKey(), "{\"type\":\"HELP\",\"message\":\"" + hostile + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(hostile));
        assertEquals(hostile, jdbc.queryForObject("SELECT message FROM service_requests", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space in it", "bad/slash/key", "ünicode-key-123456"})
    void aValidIdempotencyKeyIsRequired(String key) throws Exception {
        raiseCall(guest, key, "{\"type\":\"WAITER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", guest.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isBadRequest());
        assertNothingWritten();
    }

    @Test
    void anIdempotencyKeyLongerThanTheLimitIsRejectedButTheLimitItselfIsAccepted() throws Exception {
        raiseCall(guest, "k".repeat(121), "{\"type\":\"WAITER\"}").andExpect(status().isBadRequest());
        assertNothingWritten();
        raiseCall(guest, "k".repeat(120), "{\"type\":\"WAITER\"}").andExpect(status().isCreated());
    }

    @Test
    void nonJsonContentIsNotAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", guest.token())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.TEXT_PLAIN).content("WAITER"))
                .andExpect(status().is4xxClientError());
        assertNothingWritten();
    }

    @Test
    void sameKeyAndSameRequestIsAReplayWithTheSingleEffectOfTheFirst() throws Exception {
        String key = newKey();
        String body = "{\"type\":\"BILL\",\"message\":\"Hesap\"}";
        String first = raiseCall(guest, key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = raiseCall(guest, key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, count("service_requests"));
        assertEquals(1, count("service_call_events"));
        assertEquals(1, count("notification_deliveries"));
        assertEquals(1, count("idempotency_records"));
    }

    @Test
    void sameKeyWithADifferentRequestIsAConflictAndChangesNothing() throws Exception {
        String key = newKey();
        raiseCall(guest, key, "{\"type\":\"BILL\",\"message\":\"Hesap\"}").andExpect(status().isCreated());
        raiseCall(guest, key, "{\"type\":\"HELP\",\"message\":\"Hesap\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        raiseCall(guest, key, "{\"type\":\"BILL\",\"message\":\"Başka\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, count("service_requests"));
        assertEquals(1, count("service_call_events"));
    }

    @Test
    void theSameKeyOfAnotherGuestIsAnIndependentCommand() throws Exception {
        FakeBackends.Guest second = addGuest(table);
        String key = newKey();
        raiseCall(guest, key, "{\"type\":\"WAITER\"}").andExpect(status().isCreated());
        raiseCall(second, key, "{\"type\":\"WAITER\"}").andExpect(status().isCreated());
        assertEquals(2, count("service_requests"));
    }

    @Test
    void anActiveCallOfTheSameGuestAndTypeIsReturnedInsteadOfASecondOne() throws Exception {
        String firstBody = raiseCall(guest, newKey(), "{\"type\":\"WAITER\",\"message\":\"Birinci\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = FakeBackends.read(firstBody).get("id").asString();

        String repeatKey = newKey();
        raiseCall(guest, repeatKey, "{\"type\":\"WAITER\",\"message\":\"Ikinci\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.message").value("Birinci"));
        // The same repeated request repeats the same answer.
        raiseCall(guest, repeatKey, "{\"type\":\"WAITER\",\"message\":\"Ikinci\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1, count("service_requests"));
        assertEquals(1, count("service_call_events"), "A deduplicated request has no business effect to audit or notify");
    }

    @Test
    void anotherTypeOfTheSameGuestIsANewCall() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        raiseCall(guest, "BILL").andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());
        assertEquals(3, count("service_requests"));
    }

    @Test
    void differentGuestsAtTheSameTableAreNeverMerged() throws Exception {
        FakeBackends.Guest second = addGuest(table);
        raiseCall(guest, "BILL").andExpect(status().isCreated());
        raiseCall(second, "BILL").andExpect(status().isCreated());
        assertEquals(2, count("service_requests"));
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT guest_session_id) FROM service_requests", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT table_session_id) FROM service_requests", Integer.class));
    }

    @Test
    void afterTheCallIsResolvedTheGuestCanCallAgain() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        jdbc.update("UPDATE service_requests SET status = 'RESOLVED', resolved_by_user_id = ?, resolved_at = now(), version = version + 1",
                UUID.randomUUID());
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        assertEquals(2, count("service_requests"));
    }

    @Test
    void qrOutageIsServiceUnavailableNotAnonymousAndNotAWrite() throws Exception {
        STUB.override("POST", "/api/v1/internal/guest-sessions/verify", request -> Response.json(503, "{}"));
        raiseCall(guest, "WAITER").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andExpect(jsonPath("$.dependency").value("qr"));
        assertNothingWritten();
    }

    @Test
    void qrTimeoutIsAGatewayTimeoutWithoutInternalAddresses() throws Exception {
        STUB.override("POST", "/api/v1/internal/guest-sessions/verify", request -> Response.json(200, "{}").delayed(1500));
        String body = raiseCall(guest, "WAITER").andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("127.0.0.1"));
        assertNothingWritten();
    }

    @Test
    void qrRejectingWaiterItselfIsABadGatewayNotAGuestProblem() throws Exception {
        STUB.override("POST", "/api/v1/internal/guest-sessions/verify", request -> Response.json(401, "{}"));
        raiseCall(guest, "WAITER").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED"));
        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "not json", "[]",
            "{\"restaurantId\":\"00000000-0000-0000-0000-00000000a001\"}",
            "{\"restaurantId\":\"00000000-0000-0000-0000-00000000a001\",\"tableId\":\"00000000-0000-0000-0000-0000000000f1\","
                    + "\"tableSession\":{\"id\":\"00000000-0000-0000-0000-0000000000a1\",\"status\":\"PAUSED\"},"
                    + "\"guestSession\":{\"id\":\"00000000-0000-0000-0000-0000000000a2\",\"status\":\"ACTIVE\"}}",
            "{\"restaurantId\":\"00000000-0000-0000-0000-00000000a001\",\"tableId\":\"00000000-0000-0000-0000-0000000000f1\","
                    + "\"tableSession\":{\"id\":\"00000000-0000-0000-0000-0000000000a1\",\"status\":\"OPEN\"}}"})
    void anIncompleteOrUnknownQrAnswerIsNeverTrustedAsAnIdentity(String answer) throws Exception {
        STUB.override("POST", "/api/v1/internal/guest-sessions/verify", request -> Response.json(200, answer));
        raiseCall(guest, "WAITER").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"));
        assertNothingWritten();
    }

    @Test
    void creatingACallNeedsNeitherOrderNorNotificationToBeUp() throws Exception {
        // Order and Notification have no routes that answer; only QR's verification is needed to raise a call.
        raiseCall(guest, "BILL").andExpect(status().isCreated());
        assertTrue(STUB.requests("POST", "/api/v1/internal/service-call-events").isEmpty(),
                "Notification is told by the background queue, never inside the request");
    }
}
