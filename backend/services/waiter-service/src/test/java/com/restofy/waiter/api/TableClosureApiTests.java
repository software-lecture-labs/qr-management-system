package com.restofy.waiter.api;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeNotification;
import com.restofy.waiter.support.FakeQrCommands.Fault;
import com.restofy.waiter.support.TableCommandApiBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closing a main table session. QR is the only authority of tables, sessions and closure; Order and Payment own
 * the delivery, balance and payment facts QR verifies. These tests drive Waiter against local HTTP stubs of the
 * DRAFT contracts and prove what Waiter sends, believes, refuses and records. They do NOT prove that the real
 * QR stops new orders, settles the orders in flight or checks balance and payment atomically: that is a promise
 * QR has not made in code yet.
 */
class TableClosureApiTests extends TableCommandApiBase {

    // ----- the closure itself -----

    @Test
    void aSettledServedTableIsClosedAndTheCallsOfThatSessionEndCancelledNotResolved() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        UUID help = call(S1, "HELP", "CLAIMED");
        UUID done = call(S1, "WAITER", "RESOLVED");
        UUID olderSession = call(S2, "WAITER", "OPEN");
        UUID otherRestaurant = insertServiceRequest(OTHER_RESTAURANT, UUID.randomUUID(), S1, UUID.randomUUID(), "BILL", "OPEN",
                Instant.now());

        String key = newKey();
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CLOSE"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.tableSessionId").value(S1.toString()))
                .andExpect(jsonPath("$.expectedVersion").value(3))
                .andExpect(jsonPath("$.closure.closedAt").value("2026-10-16T11:00:00Z"))
                .andExpect(jsonPath("$.closure.endedGuestSessions").value(2))
                .andExpect(jsonPath("$.closure.cancelledCalls").value(2))
                .andExpect(jsonPath("$.cleaning").value(nullValue()))
                .andExpect(jsonPath("$.rejectionCode").value(nullValue()));

        // What Waiter sent: the command id is the idempotency key; the session and version are named; the actor is the
        // verified principal; no service credential is invented.
        assertEquals(1, closePosts().size());
        DownstreamStub.Request sent = closePosts().getFirst();
        JsonNode command = FakeBackends.read(sent.body());
        assertEquals(sent.header("Idempotency-Key"), command.get("commandId").asString());
        assertEquals(RESTAURANT.toString(), command.get("restaurantId").asString());
        assertEquals(table.id().toString(), command.get("tableId").asString());
        assertEquals(S1.toString(), command.get("tableSessionId").asString());
        assertEquals(3, command.get("expectedVersion").asInt());
        assertEquals(WAITER_A.toString(), command.get("requestedByUserId").asString());
        assertFalse(command.has("paid") || command.has("remainingAmount") || command.has("force"),
                "No claim about payment or balance and no override travels with the command");
        assertNull(sent.header("Authorization"));
        assertEquals("CLEANING", backends.table(table.id()).status(), "QR closed the session and left the table to be cleaned");

        // Only the calls of that restaurant and that session ended; they are cancelled with the reason, not resolved.
        assertEquals("CANCELLED", callStatus(bill));
        assertEquals("CANCELLED", callStatus(help));
        assertEquals("RESOLVED", callStatus(done), "A call somebody resolved stays as it was");
        assertEquals("OPEN", callStatus(olderSession), "A call of another session is out of reach");
        assertEquals("OPEN", callStatus(otherRestaurant), "A call of another restaurant is out of reach");
        Map<String, Object> cancelled = jdbc.queryForMap("SELECT * FROM service_requests WHERE id = ?", bill);
        assertEquals("TABLE_SESSION_CLOSED", cancelled.get("closure_reason"));
        assertEquals(commandId(), cancelled.get("cancelled_by_command_id"));
        assertNotNull(cancelled.get("cancelled_at"));
        assertNull(cancelled.get("resolved_by_user_id"), "Nobody resolved it");
        assertNull(cancelled.get("resolved_at"));

        Map<String, Object> row = commandRow();
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(WAITER_A, row.get("requested_by_user_id"));
        assertEquals(key, row.get("idempotency_key"));
        assertEquals(2, row.get("ended_guest_sessions"));
        assertEquals(2, row.get("cancelled_call_count"));

        // History is kept: the cancelled calls are still listed, as cancelled, next to the resolved one.
        mockMvc.perform(get("/api/v1/waiters/calls?tableSessionId=" + S1 + "&status=CANCELLED&status=RESOLVED").with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[?(@.status=='CANCELLED')].closureReason", contains("TABLE_SESSION_CLOSED",
                        "TABLE_SESSION_CLOSED")))
                .andExpect(jsonPath("$.items[?(@.status=='RESOLVED')].resolvedByUserId", not(empty())));

        // The table, as QR now reports it, is closed but not yet available.
        tableView().andExpect(jsonPath("$.table.operationalStatus").value("CLEANING"))
                .andExpect(jsonPath("$.table.session").value(nullValue()))
                .andExpect(jsonPath("$.table.readyForNewCustomer").value(false))
                .andExpect(jsonPath("$.openServiceRequests", empty()));
    }

    @Test
    void theEndOfTheCallsIsQueuedForNotificationAsCancelledAndNeverAsResolved() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        call(S1, "HELP", "CLAIMED");

        closeSession(3).andExpect(status().isOk());

        assertEquals(2, count("SELECT count(*) FROM service_call_events WHERE event_type = 'CANCELLED'"));
        assertEquals(0, count("SELECT count(*) FROM service_call_events WHERE event_type = 'RESOLVED'"));
        dispatcher.dispatchDue(Instant.now());
        List<FakeNotification.Received> received = notification.received();
        assertEquals(2, received.size());
        assertTrue(received.stream().allMatch(event -> event.callStatus().equals("CANCELLED")));
        JsonNode event = received.stream().filter(e -> e.callId().equals(bill)).findFirst().orElseThrow().body();
        assertEquals("SERVICE_CALL_CANCELLED", event.get("eventType").asString());
        assertEquals("TABLE_SESSION_CLOSED", event.get("call").get("cancellationReason").asString());
        assertTrue(event.get("call").get("resolvedByUserId").isNull());
        assertFalse(event.toString().contains("Hesap") || event.has("message"), "The guest's text never travels to Notification");
    }

    @Test
    void aNotificationOutageNeitherUndoesTheClosureNorBlocksCleaning() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        notification.failNext(FakeNotification.Fault.UNAVAILABLE, 50);

        closeSession(3).andExpect(status().isOk());
        dispatcher.dispatchDue(Instant.now());

        assertEquals("CANCELLED", callStatus(bill), "The call stays ended while Notification is down");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class));
        cleanTable(backends.table(table.id()).version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void repeatingTheSameKeyReturnsTheFirstAnswerWithoutAskingQrOrEndingCallsAgain() throws Exception {
        settledServedOrder("ORD-1");
        call(S1, "BILL", "OPEN");
        String key = newKey();
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk());
        int events = count("SELECT count(*) FROM service_call_events");

        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.commandId").value(commandId().toString()));

        assertEquals(1, closePosts().size(), "A completed command is answered from the record");
        assertEquals(events, count("SELECT count(*) FROM service_call_events"));
        assertEquals(1, count("SELECT count(*) FROM table_commands"));

        // Same key, another request: a different command, never silently the first one.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        close(asWaiter(WAITER_A), table.id(), S1, key, "{\"expectedVersion\":3,\"reason\":\"other\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, closePosts().size());
    }

    @Test
    void aManualCloseOfACancelledOrderCarriesItsReasonAndTheAuditData() throws Exception {
        addOrder(table, S1, "ORD-9", "CANCELLED", "PENDING", null, "2026-10-16T09:00:00Z");
        String key = newKey();
        close(asWaiter(WAITER_B), table.id(), S1, key, "{\"expectedVersion\":3,\"reason\":\"  Müşteri ayrıldı, sipariş iptal edildi \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        JsonNode sent = FakeBackends.read(closePosts().getFirst().body());
        assertEquals("Müşteri ayrıldı, sipariş iptal edildi", sent.get("reason").asString());
        assertEquals(WAITER_B.toString(), sent.get("requestedByUserId").asString());
        Map<String, Object> row = commandRow();
        assertEquals("Müşteri ayrıldı, sipariş iptal edildi", row.get("reason_text"));
        assertEquals(WAITER_B, row.get("requested_by_user_id"));
        assertEquals(key, row.get("idempotency_key"));
        assertNotNull(row.get("created_at"));
        assertNotNull(row.get("completed_at"));
    }

    // ----- QR decides; Waiter assesses nothing (ADR-QR-001 K7) -----

    @Test
    void waiterOffersNoClosureVerdictOfItsOwn() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/closure-eligibility").with(asWaiter(WAITER_A)))
                .andExpect(status().isNotFound());
        assertEquals(0, STUB.requests("POST", "/internal/v1/orders/search").size(), "No order is read to judge a closure");
    }

    @Test
    void qrsRefusalClosesNothingAndIsFinal() throws Exception {
        addOrder(table, S1, "ORD-2", "ACCEPTED", "SERVED", "READY", "2026-10-16T09:00:00Z");
        UUID bill = call(S1, "BILL", "OPEN");

        // Waiter does not look at the order first: it asks, and QR, which lets Order assess the session, says no.
        qrCommands.refuseCloseWith("UNPAID_BALANCE");
        String key = newKey();
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"))
                .andExpect(jsonPath("$.blockers", contains("UNPAID_BALANCE")))
                .andExpect(jsonPath("$.commandId").value(commandId().toString()));

        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals("OPEN", callStatus(bill), "Nothing ends unless QR confirmed the closure");
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals("CLOSURE_BLOCKED", commandRow().get("rejection_code"));
        assertEquals("UNPAID_BALANCE", commandRow().get("rejection_detail"));
        // The refusal is final: asking again with the same key repeats it without another request to QR.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"));
        assertEquals(1, closePosts().size());
        commandStatus(table.id(), commandId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.blockers", contains("UNPAID_BALANCE")));
    }

    @Test
    void onlyKnownBlockersOfQrsRefusalArePassedOn() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.refuseCloseWith("ORDER_IN_PREPARATION", "ORDER_ADMISSION_IN_FLIGHT", "NO_LIVE_SESSION");
        closeSession(3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers", containsInAnyOrder("ORDER_IN_PREPARATION", "ORDER_ADMISSION_IN_FLIGHT")));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void aCloseThatQrOnlyAcceptedIsShownAsPendingOnTheTable() throws Exception {
        qrCommands.failNextClose(Fault.CLOSING_ONLY);
        closeSession(3).andExpect(status().isAccepted());
        assertEquals(1, count("SELECT count(*) FROM table_commands WHERE status = 'IN_PROGRESS'"));
        tableView().andExpect(jsonPath("$.table.pendingCommand.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.table.pendingCommand.type").value("CLOSE"));
    }

    // ----- nothing a client says is evidence -----

    @Test
    void aClientCannotClaimPaymentABalanceOrAnExceptionAndCannotNameTheRestaurantOrTheActor() throws Exception {
        for (String field : List.of("\"paid\":true", "\"remainingAmount\":0", "\"force\":true", "\"ignoreBalance\":true",
                "\"restaurantId\":\"" + OTHER_RESTAURANT + "\"", "\"requestedByUserId\":\"" + UUID.randomUUID() + "\"",
                "\"tableSessionId\":\"" + S2 + "\"", "\"settlement\":{\"outstandingDue\":0}")) {
            close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3," + field + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertEquals(0, closePosts().size(), "QR was not even asked");
        assertEquals(0, count("SELECT count(*) FROM table_commands"));
        // Query parameters are no way in either.
        mockMvc.perform(post("/api/v1/waiters/tables/" + table.id() + "/sessions/" + S1 + "/close?force=true&restaurantId="
                + OTHER_RESTAURANT)
                        .with(asWaiter(WAITER_A)).with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(versionBody(3)))
                .andExpect(status().isBadRequest());
        assertEquals(0, closePosts().size());
    }

    @Test
    void theRequestIsValidatedBeforeAnythingHappens() throws Exception {
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{}").andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":-1}").andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":\"3\",\"reason\":5}").andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":42}").andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":\"" + "x".repeat(301) + "\"}")
                .andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":\"a\\nb\"}")
                .andExpect(status().isBadRequest());
        close(asWaiter(WAITER_A), table.id(), S1, "short", versionBody(3)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/waiters/tables/" + table.id() + "/sessions/" + S1 + "/close").with(asWaiter(WAITER_A))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody(3)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/waiters/tables/not-a-uuid/sessions/" + S1 + "/close").with(asWaiter(WAITER_A))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(versionBody(3)))
                .andExpect(status().isBadRequest());
        assertEquals(0, closePosts().size());
        assertEquals(0, count("SELECT count(*) FROM table_commands"));
    }

    // ----- who may do it, and for which restaurant -----

    @Test
    void onlyAVerifiedWaiterMayCloseOrAskAndNothingReachesQrOtherwise() throws Exception {
        String url = "/api/v1/waiters/tables/" + table.id() + "/sessions/" + S1 + "/close";
        mockMvc.perform(post(url).with(csrf().asHeader())
                .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(versionBody(3)))
                .andExpect(status().isUnauthorized());
        for (StaffRole role : List.of(StaffRole.KITCHEN, StaffRole.ADMIN)) {
            close(asStaff(role, RESTAURANT), table.id(), S1, newKey(), versionBody(3)).andExpect(status().isForbidden());
        }
        close(as(new AuthenticatedGuest(RESTAURANT, table.id(), S1, UUID.randomUUID()), "GUEST"),
                table.id(), S1, newKey(), versionBody(3)).andExpect(status().isForbidden());
        // A mutation without the CSRF token is refused by the unchanged security chain.
        mockMvc.perform(post(url).with(asWaiter(WAITER_A)).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON).content(versionBody(3))).andExpect(status().isForbidden());
        assertEquals(0, closePosts().size());
        assertEquals(0, count("SELECT count(*) FROM table_commands"));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
    }

    @Test
    void aWaiterOfAnotherRestaurantCannotSeeOrCloseThisTable() throws Exception {
        call(S1, "BILL", "OPEN");
        close(asWaiterOf(OTHER_RESTAURANT), table.id(), S1, newKey(), versionBody(3)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals("OPEN", callStatus(jdbc.queryForObject("SELECT id FROM service_requests", UUID.class)));
        // The command is recorded under the caller's own restaurant, never under the table's.
        assertEquals(OTHER_RESTAURANT, commandRow().get("restaurant_id"));
        // And the foreign restaurant's command id cannot be read from here.
        commandStatus(table.id(), commandId()).andExpect(status().isNotFound());
    }

    @Test
    void aSessionOfAnotherTableCannotBeClosedThroughThisTable() throws Exception {
        FakeBackends.Table other = addTable("14", "OCCUPIED", S2);
        UUID otherCall = insertServiceRequest(RESTAURANT, other.id(), S2, UUID.randomUUID(), "BILL", "OPEN", Instant.now());

        close(asWaiter(WAITER_A), table.id(), S2, newKey(), versionBody(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_OPEN"));

        assertEquals("OCCUPIED", backends.table(other.id()).status());
        assertEquals(S2, backends.table(other.id()).sessionId());
        assertEquals("OPEN", callStatus(otherCall));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
    }

    @Test
    void anOldCommandCannotCloseTheNextSessionOfTheSameTable() throws Exception {
        settledServedOrder("ORD-1");
        closeSession(3).andExpect(status().isOk());
        cleanTable(backends.table(table.id()).version()).andExpect(status().isOk());
        // The next customers sit down: QR opens a new main session on the same table.
        backends.replace(backends.table(table.id()).with("OCCUPIED", S3));
        UUID nextCall = call(S3, "WAITER", "OPEN");

        // A late or replayed command for the first session, with a new key, names the first session's id.
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), versionBody(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_OPEN"));

        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals(S3, backends.table(table.id()).sessionId(), "The new session is untouched");
        assertEquals("OPEN", callStatus(nextCall));
        // Closing the table "by its number" is not possible at all: the path needs a session id.
        mockMvc.perform(post("/api/v1/waiters/tables/12/close").with(asWaiter(WAITER_A))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(versionBody(3)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aStaleSessionVersionIsRefusedByQr() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        closeSession(2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_VERSION_CONFLICT"));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals("OPEN", callStatus(bill));
        // After re-reading the table the current version works.
        closeSession(3).andExpect(status().isOk());
    }

    @Test
    void twoWaitersClosingTheSameSessionAtOnceEndInExactlyOneClosure() throws Exception {
        for (int round = 0; round < 8; round++) {
            FakeBackends.Table contested = addTable("R" + round, "OCCUPIED", UUID.randomUUID());
            UUID session = contested.sessionId();
            UUID bill = insertServiceRequest(RESTAURANT, contested.id(), session, UUID.randomUUID(), "BILL", "OPEN", Instant.now());
            List<Answer> answers = race(List.of(
                    asTask(() -> close(asWaiter(WAITER_A), contested.id(), session, newKey(), versionBody(3))),
                    asTask(() -> close(asWaiter(WAITER_B), contested.id(), session, newKey(), versionBody(3)))));

            assertEquals(1, answers.stream().filter(a -> a.status() == 200).count(), "round " + round + ": " + answers);
            Answer loser = answers.stream().filter(a -> a.status() != 200).findFirst().orElseThrow();
            assertEquals(409, loser.status(), loser.body());
            assertTrue(List.of("CLOSE_ALREADY_OPEN", "SESSION_NOT_OPEN").contains(loser.code()), loser.body());
            assertEquals("CLEANING", backends.table(contested.id()).status());
            assertEquals("CANCELLED", callStatus(bill));
            assertEquals(1, count("SELECT count(*) FROM service_call_events WHERE service_request_id = '" + bill
                    + "' AND event_type = 'CANCELLED'"),
                    "The call ended once");
            assertEquals(1, count("SELECT count(*) FROM table_commands WHERE table_session_id = '" + session
                    + "' AND status = 'COMPLETED'"));
            assertEquals(0, count("SELECT count(*) FROM table_commands WHERE table_session_id = '" + session
                    + "' AND status IN ('PENDING','UNKNOWN','IN_PROGRESS')"));
        }
    }

    // ----- the answer of QR is lost, late or partial -----

    @Test
    void aLostAnswerIsUnknownThenSettledByQrsRecordWithoutSendingTheCloseAgain() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.LOST_ANSWER);

        // QR closed the session, but the answer did not arrive in time: not "closed", not "failed".
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), versionBody(3)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.commandId").value(commandId().toString()))
                .andExpect(jsonPath("$.dependency").value("qr"));
        UUID commandId = commandId();
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill), "Calls end only on QR's confirmation");
        // The views point at the unanswered command instead of pretending.
        tableView().andExpect(jsonPath("$.table.pendingCommand.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.table.pendingCommand.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.table.pendingCommand.type").value("CLOSE"));

        // The command id is the way to find out: QR is asked what it recorded, the close is not sent again.
        commandStatus(table.id(), commandId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closure.endedGuestSessions").value(2))
                .andExpect(jsonPath("$.closure.cancelledCalls").value(1));
        assertEquals(1, closePosts().size(), "No blind retry of a POST");
        assertEquals(1, STUB.requests("GET", CLOSE_STATUS_GET).size());
        assertEquals("CANCELLED", callStatus(bill));
        tableView().andExpect(jsonPath("$.table.pendingCommand").value(nullValue()));
    }

    @Test
    void anAnswerThatNeverCameBecauseQrNeverSawTheCommandStaysUnknownUntilTheSameRequestIsRepeated() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        String key = newKey();
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        UUID commandId = commandId();

        // QR has no record: that is not "not closed" (it may still be in flight), so nothing is claimed.
        commandStatus(table.id(), commandId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN")).andExpect(jsonPath("$.reconciliation").value("NOT_RECORDED"));
        assertEquals(1, closePosts().size());
        assertEquals("OPEN", callStatus(bill));

        // The staff member repeats the very same request: the same command id goes out again and QR deduplicates by it.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.commandId").value(commandId.toString()));
        assertEquals(2, closePosts().size());
        assertEquals(FakeBackends.read(closePosts().get(0).body()).get("commandId"), FakeBackends.read(closePosts().get(1).body())
                .get("commandId"));
        assertEquals("CANCELLED", callStatus(bill));
        assertEquals(1, count("SELECT count(*) FROM table_commands"));
    }

    @Test
    void anUnavailableQrAnUnreadableAnswerAndATimeoutAreAllUnknownAndEndNoCall() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.SERVER_ERROR);
        closeSession(3).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals(1, count("SELECT count(*) FROM table_commands WHERE status = 'UNKNOWN'"));
        jdbc.update("DELETE FROM table_commands");

        qrCommands.failNextClose(Fault.GARBAGE_ANSWER);
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals(1, count("SELECT count(*) FROM table_commands WHERE status = 'UNKNOWN'"));
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void qrRefusingWaiterItselfIsARefusalNotAnUnknownOutcome() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.UNAUTHORIZED);
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED"));
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals("DEPENDENCY_REJECTED", commandRow().get("rejection_code"));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void aClosureWithGuestSessionsLeftIsNeverReportedAsClosed() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.endedGuestsReported = 1;   // QR says CLOSED but only 1 of 2 guest sessions ended: contradictory

        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));

        assertEquals("UNKNOWN", commandRow().get("status"));
        assertNull(commandRow().get("completed_at"));
        assertEquals("OPEN", callStatus(bill), "The calls of a session whose guests are not all out stay as they are");
        // Even QR's own record, read later, is the same contradictory snapshot and is not believed either.
        commandStatus(table.id(), commandId()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"));
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void aCloseThatQrOnlyAcceptedIsInProgressNotASuccessUntilQrFinishesIt() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.CLOSING_ONLY);
        String key = newKey();

        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.closure").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()));
        UUID commandId = commandId();
        assertEquals("OPEN", callStatus(bill));
        assertNull(commandRow().get("completed_at"));
        tableView().andExpect(jsonPath("$.table.pendingCommand.status").value("IN_PROGRESS"));

        // Nothing else can be started meanwhile: no second close with another key, no cleaning.
        closeSession(3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_ALREADY_OPEN"));
        cleanTable(7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_NOT_COMPLETED"));
        assertEquals(0, cleanPosts().size());
        // Repeating the same request asks QR for its record and gets "still closing"; it does not send the close again.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isAccepted());
        assertEquals(1, closePosts().size());

        qrCommands.finishClosing(commandId);
        commandStatus(table.id(), commandId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closure.cancelledCalls").value(1));
        assertEquals("CANCELLED", callStatus(bill));
    }

    // ----- calls that race the closure -----

    @Test
    void aGuestCallCannotAttachToASessionWhoseClosureQrConfirmed() throws Exception {
        settledServedOrder("ORD-1");
        FakeBackends.Guest guest = addGuest(table);
        closeSession(3).andExpect(status().isOk());

        // The QR stub still vouches for the guest (a stale verification would): Waiter itself knows the session is closed.
        raiseCall(guest, "BILL").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_ACTIVE"));
        assertEquals(0, count("SELECT count(*) FROM service_requests WHERE status IN ('OPEN','CLAIMED')"));
    }

    @Test
    void aCallWrittenAfterTheClosureIsEndedByTheReconciler() throws Exception {
        settledServedOrder("ORD-1");
        closeSession(3).andExpect(status().isOk());
        UUID late = call(S1, "BILL", "OPEN");   // written from a guest check that was older than the closure
        UUID nextSession = call(S3, "BILL", "OPEN");

        var round = reconciler.reconcileDue(Instant.now().plusSeconds(60));

        assertEquals(1, round.lateCallsEnded());
        assertEquals(0, round.checked(), "A completed close has nothing left to ask QR");
        assertEquals("CANCELLED", callStatus(late));
        assertEquals("OPEN", callStatus(nextSession), "A call of another session is never touched");
        assertEquals(commandId(), jdbc.queryForObject("SELECT cancelled_by_command_id FROM service_requests WHERE id = ?", UUID.class,
                late));
        assertEquals(1, count("SELECT count(*) FROM service_call_events WHERE service_request_id = '" + late
                + "' AND event_type = 'CANCELLED'"));
        // Running it again finds nothing more to do.
        assertEquals(0, reconciler.reconcileDue(Instant.now().plusSeconds(120)).lateCallsEnded());
    }

    // ----- lost answers settled without any client -----

    @Test
    void aLostAnswerIsSettledByTheReconcilerEvenIfTheStaffMembersConnectionIsGoneAndNotificationIsDown() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        notification.failNext(FakeNotification.Fault.UNAVAILABLE, 50);
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());
        assertEquals("OPEN", callStatus(bill));

        // Nobody asks for the status: the background check finds QR's record and ends the session's calls.
        var round = reconciler.reconcileDue(Instant.now().plusSeconds(60));

        assertEquals(1, round.checked());
        assertEquals(1, round.settled());
        assertEquals("COMPLETED", commandRow().get("status"));
        assertEquals("CANCELLED", callStatus(bill));
        assertEquals(1, closePosts().size(), "The reconciler only asks, it never sends the close again");
        dispatcher.dispatchDue(Instant.now());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class),
                "The Notification outage only delays the delivery; the call stays ended");
        assertEquals("CANCELLED", callStatus(bill));
    }

    @Test
    void anOutageOfQrPostponesTheNextCheckAndChangesNothing() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        closeSession(3).andExpect(status().isGatewayTimeout());
        STUB.override("GET", CLOSE_STATUS_GET, request -> DownstreamStub.Response.json(503, "{}"));
        Instant firstCheck = Instant.now().plusSeconds(60);

        var postponed = reconciler.reconcileDue(firstCheck);

        assertEquals(1, postponed.checked());
        assertEquals(1, postponed.postponed());
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
        assertEquals("OCCUPIED", backends.table(table.id()).status());
        // The check was pushed back: right now (the postponement counts from the real clock) nothing is due.
        assertEquals(0, reconciler.reconcileDue(Instant.now()).checked());
    }

    @Test
    void theReconcilerNeverResendsACommandThatQrHasNoRecordOf() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        closeSession(3).andExpect(status().isGatewayTimeout());

        var round = reconciler.reconcileDue(Instant.now().plusSeconds(60));

        assertEquals(1, round.checked());
        assertEquals(0, round.settled());
        assertEquals(1, closePosts().size());
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void commandsThatAreYoungOrVeryOldAreNotChecked() throws Exception {
        call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());

        assertEquals(0, reconciler.reconcileDue(Instant.now()).checked(), "Quiet for less than the minimum age");
        jdbc.update("UPDATE table_commands SET created_at = now() - interval '3 days', updated_at = now() - interval '3 days'");
        assertEquals(0, reconciler.reconcileDue(Instant.now()).checked(), "Older than the automatic check goes");
        // It can still be settled by hand.
        commandStatus(table.id(), commandId()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aSettledCommandHidesNothingFromTheTableListAndTheCallList() throws Exception {
        settledServedOrder("ORD-1");
        call(S1, "BILL", "OPEN");
        closeSession(3).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/waiters/tables?hasOpenCall=true").with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.items[*].tableNumber", not(hasItem("12"))));
        mockMvc.perform(get("/api/v1/waiters/calls?status=OPEN&status=CLAIMED").with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
