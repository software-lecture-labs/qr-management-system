package com.restofy.waiter.api;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeQrCommands.Fault;
import com.restofy.waiter.support.TableCommandApiBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cleaning is a separate action after the closure: {@code OCCUPIED → CLEANING} (QR, when the session closes) and
 * {@code CLEANING → AVAILABLE} (QR, when Waiter reports the table cleaned). QR alone decides, and says whether
 * the table is free for the next customer; Waiter claims readiness only on QR's answer. As with the closure,
 * these tests prove Waiter against a local stub of the DRAFT contract, not the real QR.
 */
class TableCleaningApiTests extends TableCommandApiBase {

    private void closeTheSession() throws Exception {
        settledServedOrder("ORD-1");
        closeSession(3).andExpect(status().isOk());
    }

    // ----- closed is not available -----

    @Test
    void aClosedTableIsNotAvailableUntilItIsCleanedAndQrConfirmsIt() throws Exception {
        closeTheSession();
        long cleaningVersion = backends.table(table.id()).version();
        assertEquals(8, cleaningVersion, "QR counted the closure");

        // Closed, not cleaned: not ready, and cleaning is what is left to do.
        tableView().andExpect(jsonPath("$.table.operationalStatus").value("CLEANING"))
                .andExpect(jsonPath("$.table.readyForNewCustomer").value(false))
                .andExpect(jsonPath("$.table.version").value(8));

        String key = newKey();
        clean(asWaiter(WAITER_A), table.id(), key, versionBody(cleaningVersion)).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CLEAN"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.tableSessionId").value(nullValue()))
                .andExpect(jsonPath("$.expectedVersion").value(8))
                .andExpect(jsonPath("$.cleaning.readyForNewCustomer").value(true))
                .andExpect(jsonPath("$.cleaning.tableVersion").value(9))
                .andExpect(jsonPath("$.closure").value(nullValue()));

        // What Waiter sent: the table, the version it saw, the verified actor; no session, no payment claim.
        assertEquals(1, cleanPosts().size());
        DownstreamStub.Request sent = cleanPosts().getFirst();
        JsonNode command = FakeBackends.read(sent.body());
        assertEquals(sent.header("Idempotency-Key"), command.get("commandId").asString());
        assertEquals(RESTAURANT.toString(), command.get("restaurantId").asString());
        assertEquals(table.id().toString(), command.get("tableId").asString());
        assertEquals(8, command.get("expectedVersion").asInt());
        assertEquals(WAITER_A.toString(), command.get("requestedByUserId").asString());
        assertFalse(command.has("tableSessionId"));
        assertNull(sent.header("Authorization"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM table_commands WHERE command_type = 'CLEAN'");
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(key, row.get("idempotency_key"));
        assertEquals(WAITER_A, row.get("requested_by_user_id"));
        assertEquals(9L, row.get("table_version_after"));
        assertEquals(true, row.get("ready_for_new_customer"));

        // The table, as QR now reports it: available to the next customer.
        assertEquals("AVAILABLE", backends.table(table.id()).status());
        tableView().andExpect(jsonPath("$.table.operationalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.table.readyForNewCustomer").value(true))
                .andExpect(jsonPath("$.table.session").value(nullValue()));
    }

    @Test
    void aTableThatIsAvailableButDisabledOrOccupiedIsNeverReady() throws Exception {
        FakeBackends.Table free = addTable("20", "AVAILABLE", null);
        FakeBackends.Table disabled = addTable("21", "DISABLED", null);
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.items[?(@.id=='" + free.id() + "')].readyForNewCustomer").value(contains(true)))
                .andExpect(jsonPath("$.items[?(@.id=='" + disabled.id() + "')].readyForNewCustomer").value(contains(false)))
                .andExpect(jsonPath("$.items[?(@.id=='" + table.id() + "')].readyForNewCustomer").value(contains(false)));
    }

    // ----- when cleaning is refused -----

    @Test
    void cleaningIsRefusedWhileTheTableHasALiveSessionWhatEverTheStaffMemberSaw() throws Exception {
        call(S1, "BILL", "OPEN");

        cleanTable(7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_ACTIVE"))
                .andExpect(jsonPath("$.commandId").value(commandId().toString()));

        assertEquals("OCCUPIED", backends.table(table.id()).status());
        assertEquals(S1, backends.table(table.id()).sessionId());
        assertEquals("REJECTED", commandRow().get("status"));
        assertEquals(1, cleanPosts().size());
        tableView().andExpect(jsonPath("$.table.readyForNewCustomer").value(false))
                .andExpect(jsonPath("$.openServiceRequests[0].status").value("OPEN"));
    }

    @Test
    void cleaningIsRefusedWhileACloseOfTheTableHasNoFinalAnswerAndQrIsNotEvenAsked() throws Exception {
        qrCommands.failNextClose(Fault.LOST_ANSWER);
        closeSession(3).andExpect(status().isGatewayTimeout());

        cleanTable(8).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_NOT_COMPLETED"));

        assertEquals(0, cleanPosts().size());
        assertEquals(1, count("SELECT count(*) FROM table_commands"));
        // Once the close is settled, cleaning goes ahead.
        commandStatus(table.id(), commandId()).andExpect(jsonPath("$.status").value("COMPLETED"));
        cleanTable(backends.table(table.id()).version()).andExpect(status().isOk());
    }

    @Test
    void aStaleTableVersionAndATableThatIsNotCleaningAreRefusedByQr() throws Exception {
        closeTheSession();

        cleanTable(7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_VERSION_CONFLICT"));
        assertEquals("CLEANING", backends.table(table.id()).status());

        FakeBackends.Table free = addTable("30", "AVAILABLE", null);
        clean(asWaiter(WAITER_A), free.id(), newKey(), versionBody(free.version())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_CLEANING"));
        FakeBackends.Table disabled = addTable("31", "DISABLED", null);
        clean(asWaiter(WAITER_A), disabled.id(), newKey(), versionBody(disabled.version())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_CLEANING"));
        assertEquals("DISABLED", backends.table(disabled.id()).status());
    }

    @Test
    void repeatingTheSameKeyAnswersFromTheRecordAndAnotherKeyAfterTheFactIsRefusedByQr() throws Exception {
        closeTheSession();
        String key = newKey();
        clean(asWaiter(WAITER_A), table.id(), key, versionBody(8)).andExpect(status().isOk());

        clean(asWaiter(WAITER_A), table.id(), key, versionBody(8)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.cleaning.readyForNewCustomer").value(true));
        assertEquals(1, cleanPosts().size());
        clean(asWaiter(WAITER_A), table.id(), key, versionBody(9)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // A second, independent command finds the table already available: QR says it is not cleaning.
        cleanTable(9).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_NOT_CLEANING"));
        assertEquals("AVAILABLE", backends.table(table.id()).status());
    }

    @Test
    void twoWaitersCleaningTheSameTableAtOnceEndInExactlyOneCleaning() throws Exception {
        for (int round = 0; round < 8; round++) {
            FakeBackends.Table dirty = addTable("D" + round, "CLEANING", null);
            List<Answer> answers = race(List.of(
                    asTask(() -> clean(asWaiter(WAITER_A), dirty.id(), newKey(), versionBody(dirty.version()))),
                    asTask(() -> clean(asWaiter(WAITER_B), dirty.id(), newKey(), versionBody(dirty.version())))));

            assertEquals(1, answers.stream().filter(a -> a.status() == 200).count(), "round " + round + ": " + answers);
            Answer loser = answers.stream().filter(a -> a.status() != 200).findFirst().orElseThrow();
            assertEquals(409, loser.status(), loser.body());
            assertTrue(List.of("CLEAN_ALREADY_OPEN", "TABLE_VERSION_CONFLICT", "TABLE_NOT_CLEANING").contains(loser.code()), loser.body());
            assertEquals("AVAILABLE", backends.table(dirty.id()).status());
            assertEquals(1, count("SELECT count(*) FROM table_commands WHERE table_id = '" + dirty.id() + "' AND status = 'COMPLETED'"));
        }
    }

    // ----- nothing of the old session reaches the next customer -----

    @Test
    void theCallsOfTheClosedSessionStayHistoryAndNeverFollowTheTableToTheNextCustomer() throws Exception {
        settledServedOrder("ORD-1");
        UUID bill = call(S1, "BILL", "OPEN");
        closeSession(3).andExpect(status().isOk());
        cleanTable(8).andExpect(status().isOk());
        backends.replace(backends.table(table.id()).with("OCCUPIED", S3));   // the next customers sit down

        tableView().andExpect(jsonPath("$.table.session.id").value(S3.toString()))
                .andExpect(jsonPath("$.table.openCallCount").value(0))
                .andExpect(jsonPath("$.table.billRequested").value(false))
                .andExpect(jsonPath("$.openServiceRequests", empty()));
        // The history of the first session is still there, as cancelled calls of that session.
        mockMvc.perform(get("/api/v1/waiters/calls?tableSessionId=" + S1).with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(bill.toString()))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        // The old session's orders are history as well and are not listed for the live session.
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/orders").with(asWaiter(WAITER_A)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ----- lost, late and contradictory answers -----

    @Test
    void aLostCleaningAnswerIsSettledByQrsRecordWithoutSendingTheCommandAgain() throws Exception {
        closeTheSession();
        qrCommands.failNextClean(Fault.LOST_ANSWER);

        cleanTable(8).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("CLEAN_RESULT_UNKNOWN"));
        UUID cleaning = jdbc.queryForObject("SELECT id FROM table_commands WHERE command_type = 'CLEAN'", UUID.class);
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM table_commands WHERE id = ?", String.class, cleaning));
        tableView().andExpect(jsonPath("$.table.pendingCommand.type").value("CLEAN"));

        commandStatus(table.id(), cleaning).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cleaning.readyForNewCustomer").value(true));
        assertEquals(1, cleanPosts().size());
        assertEquals(1, STUB.requests("GET", CLEAN_STATUS_GET).size());
    }

    @Test
    void aSettledCleaningDoesNotClaimReadinessForATableThatIsInUseAgain() throws Exception {
        closeTheSession();
        qrCommands.failNextClean(Fault.LOST_ANSWER);
        cleanTable(8).andExpect(status().isGatewayTimeout());
        UUID cleaning = jdbc.queryForObject("SELECT id FROM table_commands WHERE command_type = 'CLEAN'", UUID.class);
        // QR applied the cleaning and the next customers already sat down before anybody asked.
        backends.replace(backends.table(table.id()).with("OCCUPIED", S3));

        commandStatus(table.id(), cleaning).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cleaning.readyForNewCustomer").value(false));
        assertEquals(false, jdbc.queryForObject("SELECT ready_for_new_customer FROM table_commands WHERE id = ?", Boolean.class, cleaning));
    }

    @Test
    void aCleaningQrNeverSawStaysUnknownUntilTheSameRequestIsRepeated() throws Exception {
        closeTheSession();
        qrCommands.failNextClean(Fault.HANG_BEFORE_APPLY);
        String key = newKey();
        clean(asWaiter(WAITER_A), table.id(), key, versionBody(8)).andExpect(status().isGatewayTimeout());
        UUID cleaning = jdbc.queryForObject("SELECT id FROM table_commands WHERE command_type = 'CLEAN'", UUID.class);

        commandStatus(table.id(), cleaning).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.reconciliation").value("NOT_RECORDED"));
        assertEquals("CLEANING", backends.table(table.id()).status());
        assertEquals(1, cleanPosts().size());

        clean(asWaiter(WAITER_A), table.id(), key, versionBody(8)).andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(cleaning.toString())).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals(2, cleanPosts().size());
        assertEquals("AVAILABLE", backends.table(table.id()).status());
    }

    @Test
    void anAnswerThatDoesNotShowTheTableAvailableIsNeverTakenAsReady() throws Exception {
        closeTheSession();
        // QR says "applied" but shows the table still cleaning: a contradictory answer.
        STUB.override("POST", CLEAN_POST, request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("commandId", FakeBackends.read(request.body()).get("commandId").asString());
            answer.put("status", "APPLIED");
            answer.put("rejectionCode", null);
            answer.put("table", FakeBackends.tableJson(backends.table(table.id())));
            return DownstreamStub.Response.json(200, FakeBackends.write(answer));
        });

        cleanTable(8).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLEAN_RESULT_UNKNOWN"));

        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM table_commands WHERE command_type = 'CLEAN'", String.class));
        tableView().andExpect(jsonPath("$.table.readyForNewCustomer").value(false));
    }

    @Test
    void qrRefusingWaiterItselfIsARefusal() throws Exception {
        closeTheSession();
        qrCommands.failNextClean(Fault.UNAUTHORIZED);
        cleanTable(8).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED"));
        assertEquals("CLEANING", backends.table(table.id()).status());
        assertEquals("DEPENDENCY_REJECTED",
                jdbc.queryForObject("SELECT rejection_code FROM table_commands WHERE command_type = 'CLEAN'", String.class));
    }

    // ----- who may do it -----

    @Test
    void onlyAVerifiedWaiterOfTheRestaurantMayCleanAndNothingReachesQrOtherwise() throws Exception {
        closeTheSession();
        String url = "/api/v1/waiters/tables/" + table.id() + "/cleaned";
        mockMvc.perform(post(url).with(csrf().asHeader()).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON).content(versionBody(8))).andExpect(status().isUnauthorized());
        for (StaffRole role : List.of(StaffRole.KITCHEN, StaffRole.ADMIN)) {
            clean(asStaff(role, RESTAURANT), table.id(), newKey(), versionBody(8)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post(url).with(asWaiter(WAITER_A)).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON).content(versionBody(8))).andExpect(status().isForbidden());
        assertEquals(0, cleanPosts().size());

        // Another restaurant's waiter finds no such table, and nothing changes.
        clean(asWaiterOf(OTHER_RESTAURANT), table.id(), newKey(), versionBody(8)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_FOUND"));
        assertEquals("CLEANING", backends.table(table.id()).status());
    }

    @Test
    void theCleaningRequestCarriesOnlyTheVersionItSaw() throws Exception {
        closeTheSession();
        for (String body : List.of("{}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":8,\"tableSessionId\":\"" + S1 + "\"}",
                "{\"expectedVersion\":8,\"status\":\"AVAILABLE\"}", "{\"expectedVersion\":8,\"restaurantId\":\"" + OTHER_RESTAURANT + "\"}",
                "{\"expectedVersion\":8,\"force\":true}")) {
            clean(asWaiter(WAITER_A), table.id(), newKey(), body).andExpect(status().isBadRequest());
        }
        clean(asWaiter(WAITER_A), table.id(), "short", versionBody(8)).andExpect(status().isBadRequest());
        assertEquals(0, cleanPosts().size());
        assertEquals("CLEANING", backends.table(table.id()).status());
    }

    @Test
    void aCommandOfAnotherTableOrRestaurantCannotBeReadThroughThisOne() throws Exception {
        closeTheSession();
        UUID closing = commandId();
        FakeBackends.Table other = addTable("40", "OCCUPIED", S2);
        commandStatus(other.id(), closing).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TABLE_COMMAND_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/commands/" + closing).with(asWaiterOf(OTHER_RESTAURANT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/commands/" + UUID.randomUUID()).with(asWaiter(WAITER_A)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/commands/" + closing + "?restaurantId=" + OTHER_RESTAURANT)
                .with(asWaiter(WAITER_A))).andExpect(status().isBadRequest());
    }
}
