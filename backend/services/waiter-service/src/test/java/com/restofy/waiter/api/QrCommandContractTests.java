package com.restofy.waiter.api;

import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeQrCommands;
import com.restofy.waiter.support.TableCommandApiBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How Waiter treats answers of QR that do not follow the DRAFT contract. A refusal is only as specific as the
 * fixed vocabulary allows, foreign text is never reflected, and an answer that contradicts the command, the
 * session or itself is no answer: the command stays unknown, nothing is closed and no call ends.
 */
class QrCommandContractTests extends TableCommandApiBase {

    private Map<String, Object> closedAnswer(UUID commandId, UUID tableId, UUID sessionId, int total, int ended) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("state", "CLOSED");
        answer.put("tableId", tableId);
        answer.put("tableSessionId", sessionId);
        answer.put("closedAt", "2026-10-16T11:00:00Z");
        answer.put("guestSessions", Map.of("total", total, "ended", ended));
        answer.put("table", FakeBackends.tableJson(backends.table(table.id()).with("CLEANING", null)));
        answer.put("rejectionCode", null);
        answer.put("blockers", List.of());
        return answer;
    }

    /** The unanswered command of the previous step would (rightly) block the next one; tests that chain steps drop it. */
    private void forgetCommands() {
        jdbc.update("DELETE FROM table_commands");
    }

    private UUID commandIdOf(DownstreamStub.Request request) {
        return UUID.fromString(FakeBackends.read(request.body()).get("commandId").asString());
    }

    @Test
    void textFromQrIsNeverReflectedAndOnlyKnownCodesAndBlockersSurvive() throws Exception {
        STUB.override("POST", CLOSE_POST, request -> Response.json(409, FakeBackends.write(Map.of(
                "status", 409, "code", "<b>evil</b>", "message", "internal host 10.0.0.5 failed",
                "blockers", List.of("UNPAID_BALANCE", "evil@example.com", "<script>")))));

        closeSession(3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QR_REJECTED"))
                .andExpect(jsonPath("$.blockers", contains("UNPAID_BALANCE")))
                .andExpect(content().string(not(containsString("evil"))))
                .andExpect(content().string(not(containsString("10.0.0.5"))));
        assertEquals("QR_REJECTED", commandRow().get("rejection_code"));
        assertEquals("UNPAID_BALANCE", commandRow().get("rejection_detail"));
    }

    @Test
    void aRefusalWithAnUnreadableBodyIsStillARefusal() throws Exception {
        STUB.override("POST", CLOSE_POST, request -> Response.json(422, "<html>nope</html>"));
        closeSession(3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_REJECTED"))
                .andExpect(jsonPath("$.blockers").isEmpty());
        assertEquals("REJECTED", commandRow().get("status"));
    }

    @ParameterizedTest
    @CsvSource({"400,409,QR_REJECTED", "404,404,SESSION_NOT_FOUND", "409,409,QR_REJECTED", "422,409,QR_REJECTED"})
    void everyDefiniteRefusalOfQrIsARejectionAndNeverASuccess(int qrStatus, int expectedStatus, String expectedCode) throws Exception {
        STUB.override("POST", CLOSE_POST, request -> Response.json(qrStatus, "{}"));
        closeSession(3).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals(0, count("SELECT count(*) FROM service_requests WHERE status = 'CANCELLED'"));
    }

    @ParameterizedTest
    @CsvSource({"401,DEPENDENCY_REJECTED,REJECTED", "403,DEPENDENCY_REJECTED,REJECTED", "418,CLOSE_RESULT_UNKNOWN,UNKNOWN",
            "500,CLOSE_RESULT_UNKNOWN,UNKNOWN", "502,CLOSE_RESULT_UNKNOWN,UNKNOWN", "504,CLOSE_RESULT_UNKNOWN,UNKNOWN"})
    void everythingThatIsNotADefiniteAnswerLeavesTheOutcomeUnknown(int qrStatus, String expectedCode,
            String commandStatus) throws Exception {
        STUB.override("POST", CLOSE_POST, request -> Response.json(qrStatus, "{}"));
        closeSession(3).andExpect(jsonPath("$.code").value(expectedCode));
        assertEquals(commandStatus, commandRow().get("status"));
    }

    @Test
    void aClosedAnswerForAnotherCommandSessionOrTableIsNoAnswer() throws Exception {
        call(S1, "BILL", "OPEN");
        // Another command id.
        STUB.override("POST", CLOSE_POST, request ->
                Response.json(200, FakeBackends.write(closedAnswer(UUID.randomUUID(), table.id(), S1, 2, 2))));
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        forgetCommands();
        // Another session.
        STUB.override("POST", CLOSE_POST, request ->
                Response.json(200, FakeBackends.write(closedAnswer(commandIdOf(request), table.id(), S2, 2, 2))));
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        forgetCommands();
        // Another table.
        STUB.override("POST", CLOSE_POST, request ->
                Response.json(200, FakeBackends.write(closedAnswer(commandIdOf(request), UUID.randomUUID(), S1, 2, 2))));
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));

        assertEquals(0, count("SELECT count(*) FROM service_requests WHERE status = 'CANCELLED'"));
        assertEquals(0, count("SELECT count(*) FROM table_commands WHERE status = 'COMPLETED'"));
    }

    @Test
    void theStatusCodeMustAgreeWithTheStateOfTheAnswer() throws Exception {
        call(S1, "BILL", "OPEN");
        // 200 that says CLOSING, 202 that says CLOSED: contradictory.
        Map<String, Object> closing = new LinkedHashMap<>(closedAnswer(UUID.randomUUID(), table.id(), S1, 2, 0));
        STUB.override("POST", CLOSE_POST, request -> {
            Map<String, Object> answer = new LinkedHashMap<>(closing);
            answer.put("commandId", commandIdOf(request));
            answer.put("state", "CLOSING");
            answer.put("closedAt", null);
            return Response.json(200, FakeBackends.write(answer));
        });
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        forgetCommands();
        STUB.override("POST", CLOSE_POST, request ->
                Response.json(202, FakeBackends.write(closedAnswer(commandIdOf(request), table.id(), S1, 2, 2))));
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals(0, count("SELECT count(*) FROM table_commands WHERE status IN ('COMPLETED','IN_PROGRESS')"));
    }

    @Test
    void aClosedAnswerWhoseTableIsNotCleaningIsNoAnswer() throws Exception {
        call(S1, "BILL", "OPEN");
        STUB.override("POST", CLOSE_POST, request -> {
            Map<String, Object> answer = closedAnswer(commandIdOf(request), table.id(), S1, 2, 2);
            answer.put("table", FakeBackends.tableJson(backends.table(table.id())));   // still occupied
            return Response.json(200, FakeBackends.write(answer));
        });
        closeSession(3).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSE_RESULT_UNKNOWN"));
        assertEquals("OPEN", callStatus(jdbc.queryForObject("SELECT id FROM service_requests", UUID.class)));
    }

    @Test
    void aStatusAnswerAboutAnotherCommandIsRefused() throws Exception {
        call(S1, "BILL", "OPEN");
        qrCommands.failNextClose(FakeQrCommands.Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());
        UUID command = commandId();
        STUB.override("GET", CLOSE_STATUS_GET, request ->
                Response.json(200, FakeBackends.write(closedAnswer(UUID.randomUUID(), table.id(), S1, 2, 2))));

        commandStatus(table.id(), command).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code")
                .value("DEPENDENCY_BAD_RESPONSE"));

        assertEquals("UNKNOWN", commandRow().get("status"));
    }

    @Test
    void aRejectedStatusAnswerEndsTheCommandWithAKnownReasonOnly() throws Exception {
        qrCommands.failNextClose(FakeQrCommands.Fault.HANG_BEFORE_APPLY);
        closeSession(3).andExpect(status().isGatewayTimeout());
        UUID command = commandId();
        STUB.override("GET", CLOSE_STATUS_GET, request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("commandId", command);
            answer.put("state", "REJECTED");
            answer.put("tableId", table.id());
            answer.put("tableSessionId", S1);
            answer.put("rejectionCode", "owner@example.com");
            answer.put("blockers", List.of("REFUND_PENDING", "other"));
            return Response.json(200, FakeBackends.write(answer));
        });

        commandStatus(table.id(), command).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionCode").value("QR_REJECTED"))
                .andExpect(jsonPath("$.blockers", contains("REFUND_PENDING")))
                .andExpect(content().string(not(containsString("owner@"))));
        assertFalse(commandRow().get("rejection_code").toString().contains("@"));
    }
}
