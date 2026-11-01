package com.restofy.waiter.api;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff view and commands of the calls: listing with filters, claim and resolve with optimistic locking and
 * idempotency. Authentication is NOT IMPLEMENTED in production (Auth contract pending), so the verified-staff
 * principal is placed in the security context by the test base, exactly as the future Auth filter will.
 */
class WaiterCallApiTests extends ApiTestBase {

    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final Instant T0 = Instant.parse("2026-10-16T09:00:00Z");

    private FakeBackends.Table table1;
    private FakeBackends.Table table2;

    @BeforeEach
    void twoOccupiedTables() {
        table1 = addTable("1", "OCCUPIED", S1);
        table2 = addTable("2", "OCCUPIED", S2);
    }

    private UUID call(UUID restaurant, FakeBackends.Table table, UUID session, String type, String status, Instant at) {
        return insertServiceRequest(restaurant, table.id(), session, UUID.randomUUID(), type, status, at);
    }

    /** A call raised the production way, by a guest through the endpoint; returns its id. */
    private UUID raise(FakeBackends.Table table, String type) throws Exception {
        String body = raiseCall(addGuest(table), type).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(FakeBackends.read(body).get("id").asString());
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM service_requests WHERE id = ?", id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/waiters/calls" + query).with(asWaiter()));
    }

    // ----- access control -----

    @Test
    void anonymousRequestsAreRejectedBeforeAnythingHappens() throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        mockMvc.perform(get("/api/v1/waiters/calls")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/waiters/calls/" + id)).andExpect(status().isUnauthorized());
        for (String action : List.of("claim", "resolve")) {
            mockMvc.perform(post("/api/v1/waiters/calls/" + id + "/" + action).with(csrf().asHeader()).header("Idempotency-Key", newKey())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals("OPEN", row(id).get("status"));
        assertEquals(0, count("service_call_events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"KITCHEN", "ADMIN"})
    void otherStaffRolesAreForbidden(String role) throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        var other = asStaff(StaffRole.valueOf(role), RESTAURANT);
        mockMvc.perform(get("/api/v1/waiters/calls").with(other)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(other)).andExpect(status().isForbidden());
        staffCommand(other, id, "claim", newKey(), 0).andExpect(status().isForbidden());
        staffCommand(other, id, "resolve", newKey(), 0).andExpect(status().isForbidden());
        assertEquals("OPEN", row(id).get("status"));
    }

    @Test
    void aGuestPrincipalCannotUseStaffEndpoints() throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        var guest = as(new AuthenticatedGuest(RESTAURANT, table1.id(), S1, UUID.randomUUID()), "GUEST");
        mockMvc.perform(get("/api/v1/waiters/calls").with(guest)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(guest)).andExpect(status().isForbidden());
        staffCommand(guest, id, "resolve", newKey(), 0).andExpect(status().isForbidden());
        assertEquals("OPEN", row(id).get("status"));
    }

    @Test
    void aStaffPrincipalWithTheWrongShapeIsForbiddenEvenWithTheWaiterAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/calls").with(as("someone", "WAITER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/waiters/calls").with(as(new AuthenticatedGuest(RESTAURANT, table1.id(), S1, UUID.randomUUID()),
                "WAITER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffMutationsStayBlockedWithoutACsrfTokenUntilAuthDecidesTheTransport() throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        mockMvc.perform(post("/api/v1/waiters/calls/" + id + "/resolve").with(asWaiter()).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertEquals("OPEN", row(id).get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurantId", "role", "tenant", "guestSessionId", "staffUserId"})
    void unknownQueryParametersAreRejected(String name) throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        list("?" + name + "=" + OTHER_RESTAURANT).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(asWaiter()).param(name, "x")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/waiters/calls/" + id + "/resolve").with(asWaiter()).with(csrf().asHeader()).param(name, "x")
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        assertEquals("OPEN", row(id).get("status"));
    }

    @Test
    void forgedIdentityHeadersChangeNeitherTheTenantNorTheActor() throws Exception {
        call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        call(OTHER_RESTAURANT, table1, S1, "HELP", "OPEN", T0);
        mockMvc.perform(get("/api/v1/waiters/calls").with(asWaiter())
                        .header("X-Restaurant-Id", OTHER_RESTAURANT.toString()).header("X-Role", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].type").value("WAITER"));

        UUID id = raise(table1, "BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0)
                .andExpect(status().isOk());
        assertEquals(WAITER_A, row(id).get("resolved_by_user_id"));
    }

    // ----- listing -----

    @Test
    void listsOnlyTheOwnRestaurantsCallsOldestFirstWithoutTouchingOtherServices() throws Exception {
        UUID late = call(RESTAURANT, table1, S1, "BILL", "OPEN", T0.plusSeconds(120));
        UUID early = call(RESTAURANT, table2, S2, "WAITER", "OPEN", T0);
        call(OTHER_RESTAURANT, table1, S1, "HELP", "OPEN", T0.plusSeconds(60));
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].id").value(contains(early.toString(), late.toString())))
                .andExpect(jsonPath("$.items[0].tableId").value(table2.id().toString()))
                .andExpect(jsonPath("$.items[0].tableSessionId").value(S2.toString()))
                .andExpect(jsonPath("$.items[0].type").value("WAITER"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].version").value(0))
                .andExpect(jsonPath("$.items[0].message").value("Yardım lütfen"))
                .andExpect(jsonPath("$.items[0].guestSessionId").doesNotExist())
                .andExpect(jsonPath("$.items[0].notification").value(nullValue()));
        assertTrue(STUB.requests().isEmpty(), "The call list is Waiter's own data and works while QR and Order are down");
    }

    @Test
    void filtersByTypeStatusTableAndSession() throws Exception {
        UUID waiterOpen = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        UUID billClaimed = call(RESTAURANT, table1, S1, "BILL", "CLAIMED", T0.plusSeconds(10));
        UUID helpResolved = call(RESTAURANT, table2, S2, "HELP", "RESOLVED", T0.plusSeconds(20));
        UUID billOpenT2 = call(RESTAURANT, table2, S2, "BILL", "OPEN", T0.plusSeconds(30));
        UUID previousSession = call(RESTAURANT, table1, UUID.randomUUID(), "HELP", "OPEN", T0.plusSeconds(40));

        list("?type=BILL").andExpect(jsonPath("$.items[*].id").value(contains(billClaimed.toString(), billOpenT2.toString())));
        list("?type=BILL&type=HELP").andExpect(jsonPath("$.totalElements").value(4));
        list("?type=WAITER,HELP").andExpect(jsonPath("$.totalElements").value(3));
        list("?status=OPEN").andExpect(jsonPath("$.items[*].id").value(contains(waiterOpen.toString(), billOpenT2.toString(),
                previousSession.toString())));
        list("?status=OPEN&status=CLAIMED").andExpect(jsonPath("$.totalElements").value(4));
        list("?status=RESOLVED").andExpect(jsonPath("$.items[*].id").value(contains(helpResolved.toString())));
        list("?status=CANCELLED").andExpect(jsonPath("$.items[*]").value(empty()));
        list("?tableId=" + table2.id()).andExpect(jsonPath("$.items[*].id").value(contains(helpResolved.toString(),
                billOpenT2.toString())));
        list("?tableSessionId=" + S1).andExpect(jsonPath("$.items[*].id").value(contains(waiterOpen.toString(), billClaimed.toString())));
        list("?tableId=" + table1.id() + "&type=BILL&status=CLAIMED")
                .andExpect(jsonPath("$.items[*].id").value(contains(billClaimed.toString())));
        list("?tableId=" + UUID.randomUUID()).andExpect(jsonPath("$.totalElements").value(0));
        // A filter never reaches into another restaurant: the same session id and type there matches nothing here.
        call(OTHER_RESTAURANT, table1, S1, "HELP", "OPEN", T0);
        list("?tableSessionId=" + S1 + "&type=HELP").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void creationWindowIsHalfOpenAndOrderingIsSelectable() throws Exception {
        UUID a = call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        UUID b = call(RESTAURANT, table1, S1, "HELP", "OPEN", T0.plusSeconds(60));
        UUID c = call(RESTAURANT, table1, S1, "BILL", "OPEN", T0.plusSeconds(120));
        list("?createdFrom=" + T0.plusSeconds(60)).andExpect(jsonPath("$.items[*].id").value(contains(b.toString(), c.toString())));
        list("?createdTo=" + T0.plusSeconds(60)).andExpect(jsonPath("$.items[*].id").value(contains(a.toString())));
        list("?createdFrom=" + T0 + "&createdTo=" + T0.plusSeconds(120))
                .andExpect(jsonPath("$.items[*].id").value(contains(a.toString(), b.toString())));
        list("?sort=CREATED_DESC").andExpect(jsonPath("$.items[*].id").value(contains(c.toString(), b.toString(), a.toString())));
        list("?sort=CREATED_ASC").andExpect(jsonPath("$.items[*].id").value(contains(a.toString(), b.toString(), c.toString())));
    }

    @Test
    void pagingTotalsDescribeTheWholeFilteredSet() throws Exception {
        for (int index = 0; index < 5; index++) call(RESTAURANT, table1, S1, "HELP", "RESOLVED", T0.plusSeconds(index));
        call(RESTAURANT, table2, S2, "BILL", "OPEN", T0);
        list("?status=RESOLVED&size=2&page=0").andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.items.length()").value(2));
        list("?status=RESOLVED&size=2&page=2").andExpect(jsonPath("$.items.length()").value(1));
        list("?status=RESOLVED&size=2&page=9").andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void equalCreationTimesAreOrderedByIdSoPagesNeverOverlap() throws Exception {
        for (int index = 0; index < 4; index++) call(RESTAURANT, table1, S1, "HELP", "RESOLVED", T0);
        List<String> ids = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            var items = FakeBackends.read(list("?size=2&page=" + page).andReturn().getResponse().getContentAsString()).get("items");
            items.forEach(item -> ids.add(item.get("id").asString()));
        }
        assertEquals(4, ids.stream().distinct().count());
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?type=COMPLAINT", "?type=waiter", "?status=ACKNOWLEDGED", "?tableId=not-a-uuid",
            "?tableSessionId=1", "?createdFrom=yesterday", "?createdTo=2026-13-45", "?sort=name", "?sort=createdAt;drop",
            "?page=-1", "?page=x", "?size=0", "?size=101", "?size=abc",
            "?createdFrom=2026-10-16T10:00:00Z&createdTo=2026-10-16T09:00:00Z",
            "?createdFrom=2026-10-16T10:00:00Z&createdTo=2026-10-16T10:00:00Z",
            "?type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER&type=WAITER"
                    + "&type=WAITER"})
    void invalidFiltersAreRejected(String query) throws Exception {
        call(RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        list(query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void detailExposesTheStateAndTheVersionToSendBack() throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "BILL", "CLAIMED", T0);
        mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.claimedByUserId").exists())
                .andExpect(jsonPath("$.claimedAt").exists())
                .andExpect(jsonPath("$.resolvedByUserId").value(nullValue()))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void anotherRestaurantsCallLooksExactlyLikeAMissingOne() throws Exception {
        UUID foreign = call(OTHER_RESTAURANT, table1, S1, "WAITER", "OPEN", T0);
        UUID missing = UUID.randomUUID();
        for (UUID id : List.of(foreign, missing)) {
            mockMvc.perform(get("/api/v1/waiters/calls/" + id).with(asWaiter()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CALL_NOT_FOUND"));
            staffCommand(asWaiter(), id, "claim", newKey(), 0)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CALL_NOT_FOUND"));
            staffCommand(asWaiter(), id, "resolve", newKey(), 0)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CALL_NOT_FOUND"));
        }
        assertEquals("OPEN", row(foreign).get("status"));
        assertEquals(0, count("service_call_events"));
        mockMvc.perform(get("/api/v1/waiters/calls/not-a-uuid").with(asWaiter())).andExpect(status().isBadRequest());
    }

    // ----- claim -----

    @Test
    void claimMovesOpenToClaimedAndRecordsWhoAndWhen() throws Exception {
        UUID id = raise(table1, "WAITER");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.claimedByUserId").value(WAITER_A.toString()))
                .andExpect(jsonPath("$.claimedAt").exists())
                .andExpect(jsonPath("$.resolvedByUserId").value(nullValue()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.notification.status").value("PENDING"))
                .andExpect(jsonPath("$.notification.callVersion").value(1));
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM service_call_events WHERE call_version = 1");
        assertEquals("CLAIMED", event.get("event_type"));
        assertEquals("OPEN", event.get("from_status"));
        assertEquals("CLAIMED", event.get("to_status"));
        assertEquals("STAFF", event.get("actor_type"));
        assertEquals(WAITER_A, event.get("actor_id"));
    }

    @Test
    void aSecondIndependentClaimIsAConflictAndNeverReplacesTheClaimer() throws Exception {
        UUID id = raise(table1, "WAITER");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        Object claimedAt = row(id).get("claimed_at");

        // Fresh view of the call (version 1): the state, not the version, is what conflicts.
        staffCommand(asWaiter(WAITER_B), id, "claim", newKey(), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_CLAIMED"));
        // Stale view of the call (version 0): the version conflicts.
        staffCommand(asWaiter(WAITER_B), id, "claim", newKey(), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // The claimer asking again with a new key is also an independent command, not a replay.
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_CLAIMED"));

        Map<String, Object> row = row(id);
        assertEquals(WAITER_A, row.get("claimed_by_user_id"));
        assertEquals(claimedAt, row.get("claimed_at"));
        assertEquals(1L, row.get("version"));
        assertEquals(2, count("service_call_events"));
    }

    @Test
    void aResolvedCallCannotBeClaimed() throws Exception {
        UUID id = raise(table1, "HELP");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        staffCommand(asWaiter(WAITER_B), id, "claim", newKey(), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_RESOLVED"));
        assertEquals("RESOLVED", row(id).get("status"));
        assertNull(row(id).get("claimed_by_user_id"));
    }

    // ----- resolve -----

    @Test
    void anyWaiterMayResolveAClaimedCallAndBothActorsStayRecorded() throws Exception {
        UUID id = raise(table1, "BILL");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.claimedByUserId").value(WAITER_A.toString()))
                .andExpect(jsonPath("$.resolvedByUserId").value(WAITER_B.toString()))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andExpect(jsonPath("$.version").value(2));
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM service_call_events WHERE call_version = 2");
        assertEquals("RESOLVED", event.get("event_type"));
        assertEquals("CLAIMED", event.get("from_status"));
        assertEquals(WAITER_B, event.get("actor_id"));
    }

    @Test
    void claimingIsOptionalSoAnOpenCallCanBeResolvedDirectly() throws Exception {
        UUID id = raise(table1, "HELP");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.claimedByUserId").value(nullValue()))
                .andExpect(jsonPath("$.resolvedByUserId").value(WAITER_A.toString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aStaleVersionIsAConflictAndSilentlyChangesNothing() throws Exception {
        UUID id = raise(table1, "WAITER");
        staffCommand(asWaiter(WAITER_A), id, "claim", newKey(), 0).andExpect(status().isOk());
        Map<String, Object> before = row(id);
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 7)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertEquals(before, row(id));
        assertEquals(2, count("service_call_events"));
    }

    @Test
    void aSecondIndependentResolveIsAConflictAndKeepsTheFirstResolverAndTime() throws Exception {
        UUID id = raise(table1, "BILL");
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        Map<String, Object> resolved = row(id);
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_RESOLVED"));
        staffCommand(asWaiter(WAITER_B), id, "resolve", newKey(), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_RESOLVED"));
        assertEquals(resolved, row(id));
        assertEquals(2, count("service_call_events"));
    }

    @Test
    void aCancelledCallCannotChangeState() throws Exception {
        UUID id = call(RESTAURANT, table1, S1, "WAITER", "CANCELLED", T0);
        staffCommand(asWaiter(), id, "claim", newKey(), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        staffCommand(asWaiter(), id, "resolve", newKey(), 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        assertEquals("CANCELLED", row(id).get("status"));
    }

    // ----- idempotency -----

    @Test
    void repeatingAFinishedCommandWithTheSameKeyHasOneBusinessEffect() throws Exception {
        UUID id = raise(table1, "BILL");
        String claimKey = newKey();
        String first = staffCommand(asWaiter(WAITER_A), id, "claim", claimKey, 0).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = staffCommand(asWaiter(WAITER_A), id, "claim", claimKey, 0).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);

        String resolveKey = newKey();
        staffCommand(asWaiter(WAITER_A), id, "resolve", resolveKey, 1).andExpect(status().isOk());
        Object resolvedAt = row(id).get("resolved_at");
        // Replays of both commands, long after the state moved on, still answer with success and change nothing.
        staffCommand(asWaiter(WAITER_A), id, "resolve", resolveKey, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        staffCommand(asWaiter(WAITER_A), id, "claim", claimKey, 0).andExpect(status().isOk());
        assertEquals(resolvedAt, row(id).get("resolved_at"));
        assertEquals(3, count("service_call_events"), "created, claimed and resolved exactly once");
        assertEquals(3, count("notification_deliveries"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM service_call_events WHERE event_type = 'RESOLVED'", Integer.class));
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflictNotAReplay() throws Exception {
        UUID id = raise(table1, "BILL");
        UUID other = raise(table2, "BILL");
        String key = newKey();
        staffCommand(asWaiter(WAITER_A), id, "resolve", key, 0).andExpect(status().isOk());
        // Different expected version, different call, same key: a different command.
        staffCommand(asWaiter(WAITER_A), id, "resolve", key, 5)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        staffCommand(asWaiter(WAITER_A), other, "resolve", key, 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals("OPEN", row(other).get("status"));
    }

    @Test
    void keysAreScopedToTheActorAndTheOperation() throws Exception {
        UUID id = raise(table1, "BILL");
        String key = newKey();
        staffCommand(asWaiter(WAITER_A), id, "claim", key, 0).andExpect(status().isOk());
        // Same key text, other operation: independent.
        staffCommand(asWaiter(WAITER_A), id, "resolve", key, 1).andExpect(status().isOk());
        // Same key text, other staff member: independent (and here a plain conflict on the resolved call).
        staffCommand(asWaiter(WAITER_B), id, "resolve", key, 2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALL_ALREADY_RESOLVED"));
    }

    @Test
    void aReplayDoesNotSkipTheTenantBoundary() throws Exception {
        UUID id = raise(table1, "BILL");
        String key = newKey();
        staffCommand(asWaiter(WAITER_A), id, "resolve", key, 0).andExpect(status().isOk());
        staffCommand(asWaiterOf(OTHER_RESTAURANT), id, "resolve", key, 0).andExpect(status().isNotFound());
    }

    // ----- request validation -----

    @Test
    void commandsRequireAKeyAndAValidExpectedVersionAndRejectIdentityInTheBody() throws Exception {
        UUID id = raise(table1, "WAITER");
        mockMvc.perform(post("/api/v1/waiters/calls/" + id + "/resolve").with(asWaiter()).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        for (String key : List.of("short", "has space in it", "bad/slash/key")) {
            staffCommand(asWaiter(), id, "resolve", key, 0).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":\"x\"}",
                "{\"expectedVersion\":0,\"resolvedByUserId\":\"" + WAITER_B + "\"}",
                "{\"expectedVersion\":0,\"restaurantId\":\"" + OTHER_RESTAURANT + "\"}",
                "{\"expectedVersion\":0,\"status\":\"CANCELLED\"}", "[]", "nope", "")) {
            var response = mockMvc.perform(post("/api/v1/waiters/calls/" + id + "/resolve").with(asWaiter()).with(csrf().asHeader())
                    .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            assertEquals(400, response.getStatus(), "body: " + body);
        }
        assertEquals("OPEN", row(id).get("status"));
        assertEquals(1, count("service_call_events"));
    }

    @Test
    void auditKeepsWhoAndWhenButNeverTheGuestCredentialOrFreeText() throws Exception {
        FakeBackends.Guest guest = addGuest(table1);
        raiseCall(guest, newKey(), "{\"type\":\"HELP\",\"message\":\"Çok gizli not 12345\"}").andExpect(status().isCreated());
        UUID id = jdbc.queryForObject("SELECT id FROM service_requests", UUID.class);
        staffCommand(asWaiter(WAITER_A), id, "resolve", newKey(), 0).andExpect(status().isOk());
        String audit = jdbc.queryForList("SELECT payload::text FROM service_call_events").toString()
                + jdbc.queryForList("SELECT actor_type, actor_id FROM service_call_events").toString();
        assertTrue(!audit.contains(guest.token()));
        assertTrue(!audit.contains("gizli"), "The guest's free text must not leave the call record");
        assertNotNull(jdbc.queryForObject("SELECT occurred_at FROM service_call_events WHERE event_type = 'RESOLVED'", Timestamp.class));
    }
}
