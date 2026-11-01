package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The WAITER-01 table indicators (open calls, bill requested, the filters built on them) are computed from the
 * very rows that the call endpoints create and change, so the table view and the call view cannot drift apart.
 */
class CallTableIndicatorsTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NEXT_SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final UUID WAITER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private FakeBackends.Table table;

    @BeforeEach
    void occupiedTable() {
        table = addTable("12", "OCCUPIED", SESSION);
        addTable("13", "AVAILABLE", null);
    }

    private UUID raise(String type) throws Exception {
        String body = raiseCall(addGuest(table), type).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(FakeBackends.read(body).get("id").asString());
    }

    private void expectIndicators(int openCalls, boolean bill) throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tableNumber").value("12"))
                .andExpect(jsonPath("$.items[0].openCallCount").value(openCalls))
                .andExpect(jsonPath("$.items[0].billRequested").value(bill))
                .andExpect(jsonPath("$.items[1].openCallCount").value(0));
    }

    @Test
    void createdClaimedAndResolvedCallsDriveTheIndicators() throws Exception {
        expectIndicators(0, false);

        UUID waiterCall = raise("WAITER");
        UUID helpCall = raise("HELP");
        UUID billCall = raise("BILL");
        // Waiter and help calls count as open calls, a bill call is the separate bill indicator.
        expectIndicators(2, true);

        staffCommand(asWaiter(WAITER), billCall, "claim", newKey(), 0).andExpect(status().isOk());
        expectIndicators(2, true);   // claimed is still unresolved

        staffCommand(asWaiter(WAITER), billCall, "resolve", newKey(), 1).andExpect(status().isOk());
        expectIndicators(2, false);

        staffCommand(asWaiter(WAITER), waiterCall, "resolve", newKey(), 0).andExpect(status().isOk());
        expectIndicators(1, false);

        staffCommand(asWaiter(WAITER), helpCall, "resolve", newKey(), 0).andExpect(status().isOk());
        expectIndicators(0, false);
    }

    @Test
    void theTableFiltersAgreeWithTheCallEndpoints() throws Exception {
        raise("HELP");
        UUID bill = raise("BILL");
        mockMvc.perform(get("/api/v1/waiters/tables?billRequested=true").with(asWaiter()))
                .andExpect(jsonPath("$.items[*].tableNumber").value(contains("12")));
        mockMvc.perform(get("/api/v1/waiters/tables?hasOpenCall=true").with(asWaiter()))
                .andExpect(jsonPath("$.items[*].tableNumber").value(contains("12")));
        mockMvc.perform(get("/api/v1/waiters/calls?status=OPEN&type=BILL&tableId=" + table.id()).with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(bill.toString()));

        staffCommand(asWaiter(WAITER), bill, "resolve", newKey(), 0).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/waiters/tables?billRequested=true").with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/waiters/calls?status=OPEN&type=BILL").with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void theTableDetailListsTheSameCallsWithTheVersionToActOn() throws Exception {
        UUID id = raise("WAITER");
        staffCommand(asWaiter(WAITER), id, "claim", newKey(), 0).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id()).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openServiceRequests.length()").value(1))
                .andExpect(jsonPath("$.openServiceRequests[0].id").value(id.toString()))
                .andExpect(jsonPath("$.openServiceRequests[0].status").value("CLAIMED"))
                .andExpect(jsonPath("$.openServiceRequests[0].version").value(1))
                .andExpect(jsonPath("$.openServiceRequests[0].guestSessionId").doesNotExist());
        mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(asWaiter()))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aCallOfAnEarlierSessionStaysInTheCallListButNeverCountsForTheNewSession() throws Exception {
        UUID old = raise("BILL");
        expectIndicators(0, true);

        // QR opens a new main session for the table: the unresolved call belongs to the previous one.
        backends.tables.clear();
        table = addTable("12", "OCCUPIED", NEXT_SESSION);
        mockMvc.perform(get("/api/v1/waiters/tables?hasOpenCall=true&billRequested=true").with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/waiters/calls?status=OPEN").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].id").value(old.toString()))
                .andExpect(jsonPath("$.items[0].tableSessionId").value(SESSION.toString()));
        mockMvc.perform(get("/api/v1/waiters/calls?tableSessionId=" + NEXT_SESSION).with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void callsOfOtherRestaurantsNeverInfluenceTheIndicators() throws Exception {
        insertServiceRequest(OTHER_RESTAURANT, SESSION, "BILL", "OPEN");
        expectIndicators(0, false);
        insertServiceRequest(RESTAURANT, table.id(), SESSION, UUID.randomUUID(), "HELP", "OPEN", Instant.now());
        expectIndicators(1, false);
    }
}
