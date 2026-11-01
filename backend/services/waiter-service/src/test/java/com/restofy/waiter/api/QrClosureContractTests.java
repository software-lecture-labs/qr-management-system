package com.restofy.waiter.api;

import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeQrCommands.Fault;
import com.restofy.waiter.support.TableCommandApiBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Waiter against QR's implemented K11 close contract and its K13 reconciliation warning (ADR-QR-001, QR commit
 * 1f98fbb): the canonical {@code /api/v1/internal} paths, the exact command body, QR's answer and refusal shapes,
 * and what a lost answer, a resend or a refusal of Waiter itself mean. The answers are hand-written in the shape of
 * QR's {@code StaffClosureResponse} and {@code ProblemDetail}; QR itself is not running, and its Waiter endpoints stay
 * closed (401) until a service identity and a staff delegation are wired. Nothing here is an end-to-end test.
 */
class QrClosureContractTests extends TableCommandApiBase {

    private static final Set<String> COMMAND_FIELDS = Set.of("commandId", "restaurantId", "tableId", "tableSessionId",
            "expectedVersion", "requestedByUserId", "reason", "requestedAt");

    /** QR's StaffClosureResponse of a CLOSED command, as its POST receipt shows it (the table left CLEANING). */
    private Map<String, Object> qrClosed(UUID commandId, List<String> blockers, Map<String, Object> extraTableFields) {
        Map<String, Object> receiptTable = new LinkedHashMap<>(FakeBackends.tableJson(backends.table(table.id()).with("CLEANING", null)));
        receiptTable.putAll(extraTableFields);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("state", "CLOSED");
        answer.put("tableId", table.id());
        answer.put("tableSessionId", S1);
        answer.put("closedAt", "2026-10-31T10:00:05Z");
        answer.put("guestSessions", Map.of("total", 2, "ended", 2));
        answer.put("table", receiptTable);
        answer.put("rejectionCode", null);
        answer.put("blockers", blockers);
        return answer;
    }

    private Map<String, Object> qrClosed(UUID commandId, List<String> blockers) {
        return qrClosed(commandId, blockers, Map.of());
    }

    /** QR's ProblemDetail for a StaffClosureException. */
    private static String qrProblem(int status, String code) {
        return FakeBackends.write(Map.of("type", "about:blank", "title", status == 404 ? "Not Found" : "Conflict",
                "status", status, "detail", "The staff closure request could not be applied.",
                "instance", "/api/v1/internal/tables/x/sessions/y/close", "code", code, "blockers", List.of()));
    }

    private static UUID commandIdOf(DownstreamStub.Request request) {
        return UUID.fromString(FakeBackends.read(request.body()).get("commandId").asString());
    }

    // ----- the command as QR expects it -----

    @Test
    void theCloseGoesDirectlyToQrsCanonicalInternalPathsWithExactlyTheCommandFields() throws Exception {
        settledServedOrder("ORD-1");
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":\"Hesap kapandı\"}")
                .andExpect(status().isGatewayTimeout());
        UUID commandId = commandId();

        DownstreamStub.Request post = closePosts().getFirst();
        assertEquals("/api/v1/internal/tables/" + table.id() + "/sessions/" + S1 + "/close", post.path());
        assertEquals(commandId.toString(), post.header("Idempotency-Key"), "Idempotency-Key == commandId");
        JsonNode body = FakeBackends.read(post.body());
        assertEquals(COMMAND_FIELDS, Set.copyOf(body.propertyNames()), "No paid, force, role or balance field");
        assertEquals(commandId.toString(), body.get("commandId").asString());
        assertEquals(RESTAURANT.toString(), body.get("restaurantId").asString());
        assertEquals(WAITER_A.toString(), body.get("requestedByUserId").asString());
        assertEquals(3, body.get("expectedVersion").asLong());
        assertEquals("Hesap kapandı", body.get("reason").asString());
        assertTrue(STUB.requests("POST", "/internal/v1/tables/.*").isEmpty(), "The old draft prefix is gone");

        commandStatus(table.id(), commandId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        DownstreamStub.Request get = STUB.requests("GET", CLOSE_STATUS_GET).getFirst();
        assertEquals("/api/v1/internal/tables/" + table.id() + "/sessions/" + S1 + "/close-commands/" + commandId, get.path());
        assertEquals(Map.of("restaurantId", RESTAURANT.toString()), get.query());
    }

    @Test
    void aRepeatAfterAnUnknownOutcomeSendsTheVeryCommandAgainNeverANewOrUpdatedOne() throws Exception {
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        String key = newKey();
        String request = "{\"expectedVersion\":3,\"reason\":\"Müşteri ayrıldı\"}";
        close(asWaiter(WAITER_A), table.id(), S1, key, request).andExpect(status().isGatewayTimeout());
        // QR's version moved on meanwhile; the repeat must still quote what the staff member saw.
        FakeBackends.Table seen = backends.table(table.id());
        backends.replace(new FakeBackends.Table(seen.id(), seen.restaurantId(), seen.number(), seen.status(),
                seen.sessionId(), seen.version(), 4));

        close(asWaiter(WAITER_A), table.id(), S1, key, request);

        List<DownstreamStub.Request> posts = closePosts();
        assertEquals(2, posts.size());
        assertEquals(FakeBackends.read(posts.get(0).body()), FakeBackends.read(posts.get(1).body()),
                "Same target, version, actor, reason and requestedAt");
        assertEquals(posts.get(0).header("Idempotency-Key"), posts.get(1).header("Idempotency-Key"));
        assertEquals(1, count("SELECT count(*) FROM table_commands"));
    }

    @Test
    void aReasonQrWouldRefuseForItsLengthIsRefusedBeforeAnythingIsSent() throws Exception {
        String emoji = "😀";   // one code point, two UTF-16 units: QR counts the units
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":\"" + emoji.repeat(151) + "\"}")
                .andExpect(status().isBadRequest());
        assertEquals(0, closePosts().size());
        assertEquals(0, count("SELECT count(*) FROM table_commands"));
        close(asWaiter(WAITER_A), table.id(), S1, newKey(), "{\"expectedVersion\":3,\"reason\":\"" + emoji.repeat(150) + "\"}")
                .andExpect(status().isOk());
    }

    // ----- QR's answers -----

    @Test
    void qrsReceiptWithAdditiveFieldsIsUnderstood() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        STUB.override("POST", CLOSE_POST, request -> {
            Map<String, Object> answer = qrClosed(commandIdOf(request), List.of(), Map.of("zone", "garden"));
            answer.put("receiptVersion", 2);
            return Response.json(200, FakeBackends.write(answer));
        });
        closeSession(3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals("CANCELLED", callStatus(bill));
    }

    @Test
    void anUnknownStateOfQrIsNoAnswer() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        STUB.override("POST", CLOSE_POST, request -> {
            Map<String, Object> answer = qrClosed(commandIdOf(request), List.of());
            answer.put("state", "SETTLED");
            return Response.json(200, FakeBackends.write(answer));
        });
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void qrsProblemDetailRefusalsAreDefiniteRejections() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        STUB.override("POST", CLOSE_POST, request -> Response.json(409, qrProblem(409, "CLOSURE_BLOCKED")));
        closeSession(3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"))
                .andExpect(jsonPath("$.blockers").isEmpty());
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));

        jdbc.update("DELETE FROM table_commands");
        STUB.override("POST", CLOSE_POST, request -> Response.json(409, qrProblem(409, "COMMAND_CONFLICT")));
        closeSession(3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"));
        assertEquals("OPEN", callStatus(bill));
    }

    @Test
    void qrsRejectedStatusAfterALostAnswerEndsTheCommandAsRefused() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        closeSession(3).andExpect(status().isGatewayTimeout());
        UUID commandId = commandId();
        STUB.override("GET", CLOSE_STATUS_GET, request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("commandId", commandId);
            answer.put("state", "REJECTED");
            answer.put("tableId", table.id());
            answer.put("tableSessionId", S1);
            answer.put("closedAt", null);
            answer.put("guestSessions", null);
            answer.put("table", FakeBackends.tableJson(backends.table(table.id())));
            answer.put("rejectionCode", "CLOSURE_BLOCKED");
            answer.put("blockers", List.of());
            return Response.json(200, FakeBackends.write(answer));
        });

        commandStatus(table.id(), commandId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionCode").value("CLOSURE_BLOCKED"));
        assertEquals("OPEN", callStatus(bill));
        assertEquals(1, closePosts().size(), "The status read never resends");
    }

    // ----- K13: closed, with a reconciliation warning -----

    @Test
    void aClosureWithQrsReconciliationWarningIsADefiniteClosureThatKeepsTheWarning() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.scopeDrift = true;
        String key = newKey();

        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rejectionCode").value(nullValue()))
                .andExpect(jsonPath("$.blockers").isEmpty())
                .andExpect(jsonPath("$.closure.reconciliationRequired").value(true))
                .andExpect(jsonPath("$.closure.endedGuestSessions").value(2));
        assertEquals("CANCELLED", callStatus(bill), "The calls end: the session is closed");
        assertEquals(Boolean.TRUE, commandRow().get("reconciliation_required"));

        // Nothing is resent, reopened or retried because of the warning; a repeat answers from the record.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.closure.reconciliationRequired").value(true));
        reconciler.reconcileDue(Instant.now().plusSeconds(3600));
        assertEquals(1, closePosts().size());
        assertEquals(0, STUB.requests("GET", CLOSE_STATUS_GET).size());
        assertEquals("CLEANING", backends.table(table.id()).status());
    }

    @Test
    void theWarningIsAlsoKeptWhenTheClosureIsLearnedFromQrsRecord() throws Exception {
        settledServedOrder("ORD-1");
        qrCommands.scopeDrift = true;
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());

        commandStatus(table.id(), commandId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closure.reconciliationRequired").value(true));
        assertEquals(1, closePosts().size());
    }

    @Test
    void aClosureWithoutTheWarningSaysSo() throws Exception {
        settledServedOrder("ORD-1");
        closeSession(3).andExpect(status().isOk()).andExpect(jsonPath("$.closure.reconciliationRequired").value(false));
        assertEquals(Boolean.FALSE, commandRow().get("reconciliation_required"));
    }

    @Test
    void aClosedAnswerNamingAClosureBlockerContradictsItselfAndClosesNothing() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        STUB.override("POST", CLOSE_POST, request ->
                Response.json(200, FakeBackends.write(qrClosed(commandIdOf(request), List.of("UNPAID_BALANCE")))));

        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));

        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
    }

    // ----- an old closure and the next visit -----

    @Test
    void anOldClosureIsConfirmedEvenWhenQrsTableAlreadyServesTheNextVisit() throws Exception {
        settledServedOrder("ORD-1");
        UUID oldCall = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());
        // Before Waiter learns the outcome, QR's table was cleaned and the next customers sat down.
        backends.replace(backends.table(table.id()).with("OCCUPIED", S3));
        UUID nextCall = call(S3, "WAITER", "OPEN");

        commandStatus(table.id(), commandId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals("CANCELLED", callStatus(oldCall));
        assertEquals("OPEN", callStatus(nextCall), "The new visit is untouched");
        assertEquals(S3, backends.table(table.id()).sessionId());
        assertEquals(1, closePosts().size());
    }

    // ----- QR refusing Waiter itself -----

    @Test
    void qrRefusingWaiterOnARepeatDoesNotTurnAnEarlierUnknownAttemptIntoARejection() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(Fault.HANG_BEFORE_APPLY);
        String key = newKey();
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isGatewayTimeout());
        qrCommands.failNextClose(Fault.UNAUTHORIZED);

        // The first attempt may still be applied by QR: a 401 to the repeat proves nothing about it.
        close(asWaiter(WAITER_A), table.id(), S1, key, versionBody(3)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
        assertEquals(2, closePosts().size());
    }

    @Test
    void withoutAServiceIdentityQrsClosedEndpointsLeaveEverythingAsItWas() throws Exception {
        UUID bill = call(S1, "BILL", "OPEN");
        // QR's denyAll / WaiterLifecycleAccess answers 401 to every request: Waiter sends no credential yet.
        STUB.override("POST", CLOSE_POST, request -> Response.json(401,
                "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Verified management context is required.\"}"));
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED"));
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals("OPEN", callStatus(bill));
        assertNull(closePosts().getFirst().header("Authorization"), "No invented credential is sent");
    }
}
