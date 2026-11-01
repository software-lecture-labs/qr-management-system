package com.restofy.waiter.api;

import com.restofy.waiter.application.service.NotificationDispatcher;
import com.restofy.waiter.application.service.NotificationDispatcher.Round;
import com.restofy.waiter.infrastructure.client.notification.NotificationClient;
import com.restofy.waiter.infrastructure.config.NotificationDispatcherProperties;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries;
import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeNotification;
import com.restofy.waiter.support.FakeNotification.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local resolve and Notification synchronization are separate facts. These tests drive the delivery queue
 * against the Notification stub (a compliant receiver of the DRAFT contract) and prove that an outage, a lost
 * answer, a restart or reordering can only delay or shrink the notifications, never reopen or alter a call.
 * They do not prove anything about the real Notification service, which has no such endpoint yet.
 */
class NotificationSyncTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private NotificationDeliveries deliveries;

    @Autowired
    private NotificationClient client;

    @Autowired
    private NotificationDispatcherProperties policy;

    private FakeBackends.Table table;
    /** A fixed "now" for dispatch rounds: safely after the events are written, and controllable for backoff checks. */
    private Instant t0;

    @BeforeEach
    void tableAndClock() {
        table = addTable("1", "OCCUPIED", SESSION);
        t0 = Instant.now().plus(Duration.ofMinutes(1));
    }

    private FakeBackends.Guest lastGuest;

    private UUID raise(String type, String message) throws Exception {
        lastGuest = addGuest(table);
        String body = raiseCall(lastGuest, newKey(), "{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(FakeBackends.read(body).get("id").asString());
    }

    private UUID raise(String type) throws Exception {
        return raise(type, "Not 4711");
    }

    private JsonNode call(UUID id) throws Exception {
        return FakeBackends.read(mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(asWaiter()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> deliveryStatuses(UUID call) {
        return jdbc.queryForList("SELECT status FROM notification_deliveries WHERE service_request_id = ? ORDER BY call_version",
                String.class, call);
    }

    private String callStatusInDatabase(UUID call) {
        return jdbc.queryForObject("SELECT status FROM service_requests WHERE id = ?", String.class, call);
    }

    private int eventRequests() {
        return STUB.requests("POST", "/api/v1/internal/service-call-events").size();
    }

    // ----- what is sent -----

    @Test
    void theCreatedEventCarriesOnlyTheMinimumFieldsAndTheEventIdAsIdempotencyKey() throws Exception {
        UUID id = raise("BILL", "Gizli not 4711");
        Round round = dispatcher.dispatchDue(t0);
        assertEquals(new Round(1, 1, 0, 0), round);

        assertEquals(1, notification.received().size());
        FakeNotification.Received received = notification.received().getFirst();
        assertEquals("APPLIED", received.outcome());
        assertEquals(received.eventId().toString(), received.idempotencyKey());
        JsonNode body = received.body();
        assertEquals(Set.of("eventId", "schemaVersion", "eventType", "occurredAt", "restaurantId", "call"), names(body));
        assertEquals(Set.of("id", "tableId", "tableSessionId", "type", "status", "version", "claimedByUserId", "claimedAt",
                "resolvedByUserId", "resolvedAt", "cancellationReason", "cancelledAt"), names(body.get("call")));
        assertTrue(body.get("call").get("cancellationReason").isNull() && body.get("call").get("cancelledAt").isNull(),
                "Only the end of a call with its table session fills the cancellation fields");
        assertEquals(1, body.get("schemaVersion").asInt());
        assertEquals("SERVICE_CALL_CREATED", body.get("eventType").asString());
        assertEquals(RESTAURANT.toString(), body.get("restaurantId").asString());
        assertEquals(id.toString(), body.get("call").get("id").asString());
        assertEquals(table.id().toString(), body.get("call").get("tableId").asString());
        assertEquals(SESSION.toString(), body.get("call").get("tableSessionId").asString());
        assertEquals("BILL", body.get("call").get("type").asString());
        assertEquals("OPEN", body.get("call").get("status").asString());
        assertEquals(0, body.get("call").get("version").asInt());

        String raw = STUB.requests("POST", "/api/v1/internal/service-call-events").getFirst().body();
        assertFalse(raw.contains("Gizli"), "The guest's free text is not sent");
        assertFalse(raw.contains(lastGuest.token()), "The guest credential is not sent");
        assertFalse(raw.contains(lastGuest.guestSessionId().toString()), "The guest identity is not sent");
        assertNull(STUB.requests("POST", "/api/v1/internal/service-call-events").getFirst().header("Authorization"),
                "No service credential is invented before the service identity contract exists");
        assertEquals(List.of("DELIVERED"), deliveryStatuses(id));
        assertEquals("DELIVERED", call(id).get("notification").get("status").asString());
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void theResolvedEventCarriesTheResolverAndTime() throws Exception {
        UUID id = raise("HELP");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 1).andExpect(status().isOk());
        dispatcher.dispatchDue(t0);

        JsonNode sent = notification.received().getLast().body();
        assertEquals("SERVICE_CALL_RESOLVED", sent.get("eventType").asString());
        assertEquals("RESOLVED", sent.get("call").get("status").asString());
        assertEquals(2, sent.get("call").get("version").asInt());
        assertEquals(WAITER_A.toString(), sent.get("call").get("claimedByUserId").asString());
        assertEquals(WAITER_B.toString(), sent.get("call").get("resolvedByUserId").asString());
        assertFalse(sent.get("call").get("resolvedAt").isNull());
    }

    @Test
    void anEventKeepsTheStateOfItsOwnVersionEvenAfterTheCallMovedOn() throws Exception {
        UUID id = raise("WAITER");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT payload->'call'->>'status' FROM service_call_events WHERE service_request_id = ? AND call_version = "
                        + "0", String.class, id));
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT payload->'call'->>'status' FROM service_call_events WHERE service_request_id = ? AND call_version = "
                        + "1", String.class, id));
    }

    // ----- ordering -----

    @Test
    void eventsAreDeliveredInVersionOrderWhenTheyAreDispatchedAsTheyHappen() throws Exception {
        UUID id = raise("BILL");
        dispatcher.dispatchDue(t0);
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        dispatcher.dispatchDue(t0.plusSeconds(1));
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 1).andExpect(status().isOk());
        dispatcher.dispatchDue(t0.plusSeconds(2));

        List<String> states = notification.received().stream().map(r -> r.callVersion() + ":" + r.callStatus()).toList();
        assertEquals(List.of("0:OPEN", "1:CLAIMED", "2:RESOLVED"), states);
        assertEquals(List.of("DELIVERED", "DELIVERED", "DELIVERED"), deliveryStatuses(id));
    }

    @Test
    void anOlderPendingStateIsNeverSentOnceANewerOneExists() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 1).andExpect(status().isOk());

        Round round = dispatcher.dispatchDue(t0);

        assertEquals(new Round(1, 1, 0, 0), round);
        assertEquals(1, eventRequests(), "Only the newest state of the call is sent");
        assertEquals("RESOLVED", notification.received().getFirst().callStatus());
        assertEquals(List.of("SUPERSEDED", "SUPERSEDED", "DELIVERED"), deliveryStatuses(id));
    }

    @Test
    void anOldOpenMessageCannotUndoARoundOfNewerResolveInformation() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        // The newer RESOLVED event was delivered first; the older OPEN one is still queued behind it.
        jdbc.update("UPDATE notification_deliveries SET status = 'DELIVERED', delivered_at = now() WHERE call_version = 1");

        Round round = dispatcher.dispatchDue(t0);

        assertEquals(new Round(0, 0, 0, 0), round);
        assertEquals(0, eventRequests());
        assertEquals(List.of("SUPERSEDED", "DELIVERED"), deliveryStatuses(id));
        assertEquals("RESOLVED", callStatusInDatabase(id));
    }

    @Test
    void notificationAnsweringStaleStillFinishesTheDelivery() throws Exception {
        UUID id = raise("HELP");
        notification.preload(id, 9);   // Notification already holds a newer version of this call.
        dispatcher.dispatchDue(t0);
        assertEquals("STALE", notification.received().getFirst().outcome());
        assertEquals(List.of("DELIVERED"), deliveryStatuses(id));
    }

    // ----- outage, timeout, lost answer -----

    @Test
    void anOutageNeverReopensAResolvedCallNorShowsTheSyncAsDone() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        notification.failNext(Fault.UNAVAILABLE, 2);

        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        JsonNode afterFirst = call(id);
        assertEquals("RESOLVED", afterFirst.get("status").asString());
        assertEquals("PENDING", afterFirst.get("notification").get("status").asString());
        assertEquals(1, afterFirst.get("notification").get("attemptCount").asInt());
        assertEquals("UNAVAILABLE", afterFirst.get("notification").get("lastErrorCode").asString());
        assertEquals(1, afterFirst.get("notification").get("callVersion").asInt());
        assertTrue(afterFirst.get("notification").get("deliveredAt").isNull());

        // Backoff: nothing is due before the delay has passed (5 s after the first failure).
        assertEquals(new Round(0, 0, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(4)));
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0.plusSeconds(5)));
        // The second failure doubles the delay (10 s).
        assertEquals(new Round(0, 0, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(14)));
        assertEquals(new Round(1, 1, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(15)));

        assertEquals("RESOLVED", callStatusInDatabase(id));
        assertEquals("DELIVERED", call(id).get("notification").get("status").asString());
        // Three attempts, one operation: the same event id and the same body every time.
        List<DownstreamStub.Request> attempts = STUB.requests("POST", "/api/v1/internal/service-call-events");
        assertEquals(3, attempts.size());
        assertEquals(1, attempts.stream().map(r -> r.header("Idempotency-Key")).distinct().count());
        assertEquals(1, attempts.stream().map(DownstreamStub.Request::body).distinct().count());
    }

    @Test
    void whileNotificationIsDownTheTableAndCallViewsKeepWorking() throws Exception {
        UUID open = raise("WAITER");
        UUID resolved = raise("HELP");
        staffCommand(asWaiter(WAITER_A), resolved, "resolve", newKey(), 0).andExpect(status().isOk());
        STUB.override("POST", "/api/v1/internal/service-call-events", request -> Response.json(503, "{}"));
        dispatcher.dispatchDue(t0);

        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].openCallCount").value(1));
        mockMvc.perform(get("/api/v1/waiters/calls?status=OPEN").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].id").value(open.toString()));
        mockMvc.perform(get("/api/v1/waiters/calls?status=RESOLVED").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].id").value(resolved.toString()))
                .andExpect(jsonPath("$.items[0].notification.status").value("PENDING"));
        staffCommand(asWaiter(WAITER_B), open, "resolve", newKey(), 0).andExpect(status().isOk());
    }

    @Test
    void aReadTimeoutIsRetriedWithoutAnyEffectOnTheCall() throws Exception {
        UUID id = raise("HELP");
        notification.failNext(Fault.HANG, 1);
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals("TIMEOUT", jdbc.queryForObject("SELECT last_error_code FROM notification_deliveries", String.class));
        assertEquals("OPEN", callStatusInDatabase(id));
        assertEquals(new Round(1, 1, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(5)));
    }

    @Test
    void aLostAnswerIsResentWithTheSameEventAndRecognisedAsADuplicate() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        notification.failNext(Fault.LOST_ANSWER, 1);

        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals("TIMEOUT", jdbc.queryForObject("SELECT last_error_code FROM notification_deliveries WHERE status = 'PENDING'",
                String.class));
        assertEquals("RESOLVED", callStatusInDatabase(id));
        assertEquals(new Round(1, 1, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(5)));

        List<FakeNotification.Received> received = notification.received();
        assertEquals(List.of("APPLIED", "DUPLICATE"), received.stream().map(FakeNotification.Received::outcome).toList());
        assertEquals(received.get(0).eventId(), received.get(1).eventId());
        assertEquals("DELIVERED", call(id).get("notification").get("status").asString());
    }

    @ParameterizedTest
    @CsvSource({"REJECTED,REJECTED", "BAD_REQUEST,BAD_RESPONSE"})
    void otherFailuresAreRecordedWithAStableCodeAndRetried(Fault fault, String expectedCode) throws Exception {
        raise("WAITER");
        notification.failNext(fault, 1);
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals(expectedCode, jdbc.queryForObject("SELECT last_error_code FROM notification_deliveries", String.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "200|{}",
            "200|not json",
            "200|{\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"outcome\":\"APPLIED\"}",
            "200|{\"outcome\":\"APPLIED\"}",
            "200|{\"outcome\":\"MAYBE\"}",
            "404|{}",
            "409|{}"})
    void anUnusableAnswerIsNeverTakenAsDelivered(int status, String answer) throws Exception {
        raise("HELP");
        STUB.override("POST", "/api/v1/internal/service-call-events", request -> Response.json(status, answer));
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals("BAD_RESPONSE", jdbc.queryForObject("SELECT last_error_code FROM notification_deliveries", String.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
    }

    @Test
    void anEmptyAnswerBodyIsNeverTakenAsDelivered() throws Exception {
        raise("HELP");
        STUB.override("POST", "/api/v1/internal/service-call-events", request -> Response.json(200, ""));
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
    }

    @Test
    void anAnswerForAnotherEventIsNotAcceptedAsTheAcknowledgement() throws Exception {
        raise("HELP");
        STUB.override("POST", "/api/v1/internal/service-call-events",
                request -> Response.json(200, "{\"eventId\":\"" + UUID.randomUUID() + "\",\"outcome\":\"APPLIED\"}"));
        assertEquals(new Round(1, 0, 1, 0), dispatcher.dispatchDue(t0));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
    }

    // ----- bounded retries and visible failure -----

    @Test
    void afterTheLastAttemptTheEventIsParkedAsDeadAndStaysVisibleWhileTheCallStaysResolved() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        jdbc.update("UPDATE notification_deliveries SET attempt_count = ? WHERE status = 'PENDING'", policy.maxAttempts() - 1);
        notification.failNext(Fault.UNAVAILABLE, 1);

        assertEquals(new Round(1, 0, 0, 1), dispatcher.dispatchDue(t0));

        JsonNode view = call(id);
        assertEquals("RESOLVED", view.get("status").asString());
        assertEquals("DEAD", view.get("notification").get("status").asString());
        assertEquals(policy.maxAttempts(), view.get("notification").get("attemptCount").asInt());
        assertEquals("UNAVAILABLE", view.get("notification").get("lastErrorCode").asString());
        mockMvc.perform(get("/api/v1/waiters/calls?status=RESOLVED").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].notification.status").value("DEAD"));
        // Parked, not retried forever and not dropped.
        assertEquals(new Round(0, 0, 0, 0), dispatcher.dispatchDue(t0.plus(Duration.ofDays(1))));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE status = 'DEAD'", Integer.class));
        assertEquals("RESOLVED", callStatusInDatabase(id));
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter())).andExpect(jsonPath("$.items[0].openCallCount").value(0));
    }

    // ----- restart and crash -----

    @Test
    void aNewProcessPicksUpTheQueueFromTheDatabaseAlone() throws Exception {
        UUID id = raise("BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        notification.failNext(Fault.UNAVAILABLE, 1);
        dispatcher.dispatchDue(t0);
        assertEquals("PENDING", deliveryStatuses(id).getLast());

        // "Restart": a dispatcher with no memory at all, built from the same persistent collaborators.
        NotificationDispatcher restarted = new NotificationDispatcher(deliveries, client, policy);
        assertEquals(new Round(1, 1, 0, 0), restarted.dispatchDue(t0.plusSeconds(60)));
        assertEquals("DELIVERED", call(id).get("notification").get("status").asString());
        assertEquals("RESOLVED", callStatusInDatabase(id));
    }

    @Test
    void anAttemptInterruptedByACrashBecomesDueAgainAfterTheLeaseAndKeepsTheEventId() throws Exception {
        UUID id = raise("HELP");
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM notification_deliveries", UUID.class);
        // The process leased the event and died before it could record any result.
        assertEquals(1, deliveries.leaseDue(t0, 20, policy.lease()).size());

        assertEquals(new Round(0, 0, 0, 0), dispatcher.dispatchDue(t0.plusSeconds(1)), "Still leased by the dead attempt");
        assertEquals(new Round(1, 1, 0, 0), dispatcher.dispatchDue(t0.plus(policy.lease()).plusSeconds(1)));
        assertEquals(eventId, notification.received().getFirst().eventId());
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM notification_deliveries", Integer.class),
                "The interrupted attempt counts");
        assertEquals("OPEN", callStatusInDatabase(id));
    }

    // ----- read is not resolve -----

    @Test
    void aNotificationThatWasDeliveredAndReadLeavesTheCallOpen() throws Exception {
        UUID id = raise("WAITER");
        dispatcher.dispatchDue(t0);   // Notification applied it; staff may read it there, which Waiter never hears about.
        assertEquals("OPEN", call(id).get("status").asString());
        assertEquals("DELIVERED", call(id).get("notification").get("status").asString());
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].openCallCount").value(1));
    }

    @Test
    void waiterOffersNoRoadForNotificationToResolveOrMarkACall() throws Exception {
        UUID id = raise("WAITER");
        for (String path : List.of("/api/v1/waiters/calls/" + id + "/read", "/api/v1/waiters/calls/" + id + "/acknowledge",
                "/api/v1/waiters/notifications", "/api/v1/waiters/notifications/" + id + "/read")) {
            mockMvc.perform(post(path).with(asWaiter()).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())
                            .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                    .andExpect(status().is4xxClientError());
        }
        mockMvc.perform(put("/api/v1/waiters/calls/" + id).with(asWaiter())
                        .with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().is4xxClientError());
        assertEquals("OPEN", callStatusInDatabase(id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM service_call_events", Integer.class));
    }

    @Test
    void theQueueWritesNothingWhenNoEventIsDue() {
        assertEquals(new Round(0, 0, 0, 0), dispatcher.dispatchDue(t0));
        assertTrue(new ArrayList<>(notification.received()).isEmpty());
    }
}
