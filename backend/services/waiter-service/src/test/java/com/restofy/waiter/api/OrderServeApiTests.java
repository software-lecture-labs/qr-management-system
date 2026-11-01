package com.restofy.waiter.api;

import com.restofy.waiter.application.service.IdempotencyKeys;
import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeBackends.PartialCancellation;
import com.restofy.waiter.support.FakeBackends.ServeFault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
 * Serving a ready order. Order is the only authority of the served fact; these tests drive Waiter against a
 * local HTTP stub that behaves like a compliant implementation of the DRAFT Order contract (atomic decision,
 * idempotent by command id). They prove what Waiter does with Order's answers, silences and refusals. They do
 * NOT prove that the real Order decides atomically, deduplicates by command id or applies the transition
 * matrix: that is a promise Order has not made in code yet.
 */
class OrderServeApiTests extends ApiTestBase {

    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String SERVE_POST = "/api/v1/internal/orders/[0-9a-f-]{36}/serve";

    private FakeBackends.Table table;
    private FakeBackends.Order order;

    @BeforeEach
    void tableWithAReadyOrder() {
        table = addTable("1", "OCCUPIED", S1);
        order = readyOrder("ORD-1");
    }

    private FakeBackends.Order readyOrder(String number) {
        return addOrder(table, S1, number, "ACCEPTED", "PENDING", "READY", "2026-10-16T09:00:00Z");
    }

    private static String body(UUID session, long version) {
        return "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":" + version + "}";
    }

    private ResultActions serve(RequestPostProcessor staff, FakeBackends.Order target, String key, long version) throws Exception {
        return serve(staff, target.id(), key, body(target.sessionId(), version));
    }

    private ResultActions serve(RequestPostProcessor staff, UUID orderId, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/waiters/orders/" + orderId + "/served").with(staff).with(csrf().asHeader())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions serveOnce(FakeBackends.Order target) throws Exception {
        return serve(asWaiter(WAITER_A), target, newKey(), target.version());
    }

    private List<DownstreamStub.Request> servePosts() {
        return STUB.requests("POST", SERVE_POST);
    }

    private Map<String, Object> commandRow() {
        return jdbc.queryForMap("SELECT * FROM order_serve_commands");
    }

    private int commandCount() {
        return jdbc.queryForObject("SELECT count(*) FROM order_serve_commands", Integer.class);
    }

    private String commandStatus() {
        return jdbc.queryForObject("SELECT status FROM order_serve_commands", String.class);
    }

    private UUID commandId() {
        return jdbc.queryForObject("SELECT id FROM order_serve_commands", UUID.class);
    }

    private JsonNode orderView(UUID id) throws Exception {
        return FakeBackends.read(mockMvc.perform(get("/api/v1/waiters/orders/" + id).with(asWaiter()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ----- success -----

    @Test
    void aReadyPendingOrderIsServedAndTheAnswerIsOrdersOwn() throws Exception {
        String key = newKey();
        serve(asWaiter(WAITER_A), order, key, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.tableSessionId").value(S1.toString()))
                .andExpect(jsonPath("$.expectedVersion").value(4))
                .andExpect(jsonPath("$.order.deliveryStatus").value("SERVED"))
                .andExpect(jsonPath("$.order.servedAt").value("2026-10-16T10:30:00Z"))
                .andExpect(jsonPath("$.order.version").value(5))
                .andExpect(jsonPath("$.order.preparationStatus").value("READY"))
                .andExpect(jsonPath("$.rejectionCode").value(nullValue()));

        // What Waiter sent: the command id doubles as the idempotency key; the actor is the verified principal.
        assertEquals(1, servePosts().size());
        DownstreamStub.Request sent = servePosts().getFirst();
        JsonNode command = FakeBackends.read(sent.body());
        assertEquals(sent.header("Idempotency-Key"), command.get("commandId").asString());
        assertEquals(RESTAURANT.toString(), command.get("restaurantId").asString());
        assertEquals(S1.toString(), command.get("tableSessionId").asString());
        assertEquals(4, command.get("expectedVersion").asInt());
        assertEquals(WAITER_A.toString(), command.get("requestedByUserId").asString());
        assertNull(sent.header("Authorization"), "No service credential is invented before the service identity contract exists");
        // Technical record: who, which key, how it ended.
        Map<String, Object> row = commandRow();
        assertEquals("CONFIRMED", row.get("status"));
        assertEquals(WAITER_A, row.get("requested_by_user_id"));
        assertEquals(key, row.get("idempotency_key"));
        assertEquals(UUID.fromString(command.get("commandId").asString()), row.get("id"));
        assertNull(row.get("rejection_code"));
    }

    @Test
    void afterServingTheOrderLeavesTheDeliveryQueueAndKitchenStaysReady() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/orders?view=AWAITING_DELIVERY").with(asWaiter()))
                .andExpect(jsonPath("$.items[*].id").value(contains(order.id().toString())));

        serveOnce(order).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/waiters/orders?view=AWAITING_DELIVERY").with(asWaiter()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/waiters/orders?view=READY_HISTORY").with(asWaiter()))
                .andExpect(jsonPath("$.items[*].id").value(contains(order.id().toString())))
                .andExpect(jsonPath("$.items[0].deliveryStatus").value("SERVED"));
        JsonNode view = orderView(order.id());
        assertEquals("SERVED", view.get("deliveryStatus").asString());
        assertEquals("READY", view.get("preparation").get("status").asString(), "Serving never changes Kitchen's READY");
        assertEquals(5, view.get("version").asInt());
        assertTrue(view.get("serveCommand").isNull(), "A confirmed command is Order's answer, not an open question");
        assertTrue(STUB.requests().stream().noneMatch(r -> r.path().contains("kitchen")), "Waiter never talks to Kitchen");
    }

    @Test
    void waiterStoresNoDeliveryFactOfItsOwn() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'order_serve_commands'", String.class);
        assertTrue(columns.stream().noneMatch(c -> c.contains("served") || c.contains("delivery") || c.contains("payment")),
                "The command record must not become a second source of the served fact: " + columns);
    }

    @Test
    void prepaymentNeitherBlocksNorRequiresServing() throws Exception {
        FakeBackends.Order paid = addOrder(table, S1, "ORD-PAID", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:05:00Z");
        backends.orders.set(backends.orders.indexOf(paid), paid.withPayment("PAID"));
        serve(asWaiter(), paid, newKey(), 4).andExpect(status().isOk());

        FakeBackends.Order unpaid = addOrder(table, S1, "ORD-UNPAID", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:06:00Z");
        serve(asWaiter(), unpaid, newKey(), 4).andExpect(status().isOk());

        // Paid is not a reason to serve what Kitchen has not finished.
        FakeBackends.Order paidButCooking = addOrder(table, S1, "ORD-COOKING", "ACCEPTED", "PENDING", "PREPARING", "2026-10-16T09:07:00Z");
        backends.orders.set(backends.orders.indexOf(paidButCooking), paidButCooking.withPayment("PAID"));
        serve(asWaiter(), paidButCooking, newKey(), 4)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));
    }

    // ----- Order refuses -----

    @ParameterizedTest
    @CsvSource({
            "ACCEPTED,PENDING,WAITING,0,true,ORDER_NOT_READY",
            "ACCEPTED,PENDING,PREPARING,0,true,ORDER_NOT_READY",
            "ACCEPTED,PENDING,,0,true,ORDER_NOT_READY",
            "CANCELLED,PENDING,READY,0,true,ORDER_CANCELLED",
            "ACCEPTED,SERVED,READY,0,true,ORDER_ALREADY_SERVED",
            "ACCEPTED,PENDING,READY,-1,true,ORDER_VERSION_CONFLICT",
            "ACCEPTED,PENDING,READY,0,false,ORDER_SESSION_MISMATCH"})
    void orderRefusalsAreDefiniteAndRecordedWithTheirReason(String business, String delivery, String preparation,
                                                            long versionDelta, boolean rightSession, String code) throws Exception {
        FakeBackends.Order target = addOrder(table, S1, "ORD-X", business, delivery, preparation, "2026-10-16T09:10:00Z");
        UUID session = rightSession ? S1 : UUID.randomUUID();
        FakeBackends.Order before = backends.order(target.id());

        serve(asWaiter(), target.id(), newKey(), body(session, target.version() + versionDelta))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.commandId").exists());

        assertEquals("REJECTED", commandStatus());
        assertEquals(code, commandRow().get("rejection_code"));
        assertEquals(before, backends.order(target.id()), "A refused command changes nothing at Order");
        assertEquals(1, servePosts().size());
    }

    @Test
    void waiterDoesNotDecideReadinessItselfItAsksOrderEvenWhenItsOwnViewIsStale() throws Exception {
        // Waiter's last read said "preparing"; Order meanwhile knows it is ready. Only Order's decision counts.
        FakeBackends.Order cooking = addOrder(table, S1, "ORD-STALE", "ACCEPTED", "PENDING", "PREPARING", "2026-10-16T09:20:00Z");
        mockMvc.perform(get("/api/v1/waiters/orders/" + cooking.id()).with(asWaiter()))
                .andExpect(jsonPath("$.preparation.status").value("PREPARING"));
        backends.orders.set(backends.orders.indexOf(cooking), new FakeBackends.Order(cooking.id(), cooking.restaurantId(),
                cooking.tableId(), cooking.sessionId(), cooking.number(), "ACCEPTED", "PENDING", "READY",
                cooking.acceptedAt(), cooking.acceptedAt().plusSeconds(600), null));
        serve(asWaiter(), cooking, newKey(), 4).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"ALLOW,200", "REJECT,409"})
    void partialCancellationIsOrdersDecisionNotWaiters(PartialCancellation policy, int expected) throws Exception {
        backends.partialCancellation = policy;
        FakeBackends.Order partial = addOrder(table, S1, "ORD-PART", "PARTIALLY_CANCELLED", "PENDING", "READY", "2026-10-16T09:30:00Z");
        serve(asWaiter(), partial, newKey(), 4).andExpect(status().is(expected));
        assertEquals(1, servePosts().size(), "Waiter forwards the command in both cases");
        if (expected == 409) {
            assertEquals("ORDER_NOT_DELIVERABLE", commandRow().get("rejection_code"));
            assertEquals("PENDING", backends.order(partial.id()).delivery());
        } else {
            assertEquals("SERVED", backends.order(partial.id()).delivery());
        }
    }

    @Test
    void remainingAndCancelledQuantitiesComeFromOrdersOwnNumbers() throws Exception {
        FakeBackends.Order partial = addOrder(table, S1, "ORD-PART", "PARTIALLY_CANCELLED", "PENDING", "READY", "2026-10-16T09:30:00Z");
        FakeBackends.Order cancelled = addOrder(table, S1, "ORD-CANC", "CANCELLED", "PENDING", "READY", "2026-10-16T09:31:00Z");
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id()).with(asWaiter()))
                .andExpect(jsonPath("$.items[0].quantity").value(2)).andExpect(jsonPath("$.items[0].cancelledQuantity").value(0))
                .andExpect(jsonPath("$.items[0].remainingQuantity").value(2));
        mockMvc.perform(get("/api/v1/waiters/orders/" + partial.id()).with(asWaiter()))
                .andExpect(jsonPath("$.businessStatus").value("PARTIALLY_CANCELLED"))
                .andExpect(jsonPath("$.items[0].cancelledQuantity").value(1))
                .andExpect(jsonPath("$.items[0].remainingQuantity").value(1))
                .andExpect(jsonPath("$.cancellations[0].items[0].quantity").value(1))
                .andExpect(jsonPath("$.cancelledGrossTotal").value(75.00));
        mockMvc.perform(get("/api/v1/waiters/orders/" + cancelled.id()).with(asWaiter()))
                .andExpect(jsonPath("$.items[0].remainingQuantity").value(0));
    }

    @Test
    void anotherRestaurantsOrderAndAnUnknownOrderBothLookMissing() throws Exception {
        FakeBackends.Table foreignTable = addTable(OTHER_RESTAURANT, "1", "OCCUPIED", UUID.randomUUID());
        FakeBackends.Order foreign = addOrder(foreignTable, foreignTable.sessionId(), "F-1", "ACCEPTED", "PENDING", "READY",
                "2026-10-16T09:00:00Z");
        serve(asWaiter(), foreign, newKey(), 4)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        serve(asWaiter(), UUID.randomUUID(), newKey(), body(S1, 4))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertEquals("PENDING", backends.order(foreign.id()).delivery());
        // The restaurant Order was asked about is the caller's, never the order owner's.
        for (DownstreamStub.Request post : servePosts()) {
            assertEquals(RESTAURANT.toString(), FakeBackends.read(post.body()).get("restaurantId").asString());
        }
    }

    // ----- access control and request validation -----

    @Test
    void onlyAVerifiedWaiterMayServeAndNothingReachesOrderOtherwise() throws Exception {
        String json = body(S1, 4);
        mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served").with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        for (StaffRole role : List.of(StaffRole.KITCHEN, StaffRole.ADMIN)) {
            serve(asStaff(role, RESTAURANT), order.id(), newKey(), json).andExpect(status().isForbidden());
        }
        var guest = as(new AuthenticatedGuest(RESTAURANT, table.id(), S1, UUID.randomUUID()), "GUEST");
        serve(guest, order.id(), newKey(), json).andExpect(status().isForbidden());
        serve(as("someone", "WAITER"), order.id(), newKey(), json).andExpect(status().isForbidden());
        // Without a CSRF token a mutation stays blocked until Auth decides the transport.
        mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served").with(asWaiter())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden());
        assertTrue(servePosts().isEmpty());
        assertEquals(0, commandCount());
        assertEquals("PENDING", backends.order(order.id()).delivery());
    }

    @Test
    void theActorIsAlwaysTheVerifiedPrincipalNotWhatTheRequestClaims() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served").with(asWaiter(WAITER_A)).with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).header("X-User-Id", WAITER_B.toString()).header("X-Role", "ADMIN")
                        .header("X-Restaurant-Id", OTHER_RESTAURANT.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(S1, 4)))
                .andExpect(status().isOk());
        JsonNode sent = FakeBackends.read(servePosts().getFirst().body());
        assertEquals(WAITER_A.toString(), sent.get("requestedByUserId").asString());
        assertEquals(RESTAURANT.toString(), sent.get("restaurantId").asString());
    }

    @Test
    void invalidRequestsNeverReachOrder() throws Exception {
        String session = S1.toString();
        for (String json : List.of("{}", "{\"expectedVersion\":4}", "{\"tableSessionId\":\"" + session + "\"}",
                "{\"tableSessionId\":null,\"expectedVersion\":4}", "{\"tableSessionId\":\"nope\",\"expectedVersion\":4}",
                "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":-1}",
                "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":\"x\"}",
                "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":4,\"restaurantId\":\"" + OTHER_RESTAURANT + "\"}",
                "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":4,\"requestedByUserId\":\"" + WAITER_B + "\"}",
                "{\"tableSessionId\":\"" + session + "\",\"expectedVersion\":4,\"deliveryStatus\":\"SERVED\"}", "[]", "nope", "")) {
            var response = serve(asWaiter(), order.id(), newKey(), json).andReturn().getResponse();
            assertEquals(400, response.getStatus(), "body: " + json);
        }
        for (String key : List.of("short", "has space in it", "bad/slash/key", "ünicode-key-123456")) {
            serve(asWaiter(), order.id(), key, body(S1, 4)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served").with(asWaiter()).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body(S1, 4)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/waiters/orders/not-a-uuid/served").with(asWaiter()).with(csrf().asHeader())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content(body(S1, 4)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served?restaurantId=" + OTHER_RESTAURANT)
                        .with(asWaiter()).with(csrf().asHeader()).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body(S1, 4)))
                .andExpect(status().isBadRequest());
        assertTrue(servePosts().isEmpty());
        assertEquals(0, commandCount());
    }

    // ----- idempotency -----

    @Test
    void repeatingASuccessfulCommandRepeatsTheAnswerAndSendsNothingNew() throws Exception {
        String key = newKey();
        String first = serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(FakeBackends.read(first).get("commandId"), FakeBackends.read(second).get("commandId"));
        assertEquals("CONFIRMED", FakeBackends.read(second).get("status").asString());
        assertEquals(5, FakeBackends.read(second).get("order").get("version").asInt());
        assertEquals(1, servePosts().size(), "The repeat is answered from the record; no second command is sent");
        assertEquals(1, commandCount());
        assertEquals(5L, backends.order(order.id()).version(), "Order applied the transition exactly once");
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflictAndSendsNothing() throws Exception {
        String key = newKey();
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk());
        FakeBackends.Order other = readyOrder("ORD-2");
        serve(asWaiter(WAITER_A), order, key, 3)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        serve(asWaiter(WAITER_A), other, key, 4)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        serve(asWaiter(WAITER_A), order.id(), key, body(UUID.randomUUID(), 4))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, servePosts().size());
        assertEquals("PENDING", backends.order(other.id()).delivery());
    }

    @Test
    void aRefusedCommandRepeatsItsRefusalWithoutAskingOrderAgain() throws Exception {
        FakeBackends.Order cooking = addOrder(table, S1, "ORD-COOK", "ACCEPTED", "PENDING", "PREPARING", "2026-10-16T09:00:00Z");
        String key = newKey();
        serve(asWaiter(WAITER_A), cooking, key, 4).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));
        serve(asWaiter(WAITER_A), cooking, key, 4).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));
        assertEquals(1, servePosts().size());
        // A new key is a new command and gets its own answer.
        serve(asWaiter(WAITER_A), cooking, newKey(), 4).andExpect(status().isConflict());
        assertEquals(2, servePosts().size());
    }

    @Test
    void keysBelongToTheStaffMemberWhoUsedThem() throws Exception {
        String key = newKey();
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk());
        // Another waiter with the same key text is an independent command: it meets the served order, not a replay.
        serve(asWaiter(WAITER_B), order, key, 5)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_ALREADY_SERVED"));
        assertEquals(2, commandCount());
    }

    @Test
    void servingTwiceWithNewKeysIsAConflictWhateverVersionIsSent() throws Exception {
        serve(asWaiter(WAITER_A), order, newKey(), 4).andExpect(status().isOk());
        serve(asWaiter(WAITER_B), order, newKey(), 4)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));
        serve(asWaiter(WAITER_B), order, newKey(), 5)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_ALREADY_SERVED"));
        assertEquals(WAITER_A.toString(), "" + jdbc.queryForObject(
                "SELECT requested_by_user_id FROM order_serve_commands WHERE status = 'CONFIRMED'", UUID.class));
    }

    // ----- unknown outcome -----

    @Test
    void aLostAnswerIsNeitherSuccessNorFailureAndIsNeverRetriedBlindly() throws Exception {
        backends.failNextServe(ServeFault.LOST_ANSWER);
        serve(asWaiter(WAITER_A), order, newKey(), 4)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.commandId").exists())
                .andExpect(jsonPath("$.dependency").value("order"))
                .andExpect(jsonPath("$.status").value(504));

        assertEquals("UNKNOWN", commandStatus());
        assertEquals(1, servePosts().size(), "Waiter does not resend on its own");
        assertEquals("SERVED", backends.order(order.id()).delivery(), "Order did apply it; the answer was just lost");

        // The order view stays Order's truth and points to the open question.
        JsonNode view = orderView(order.id());
        assertEquals("SERVED", view.get("deliveryStatus").asString());
        assertEquals("UNKNOWN", view.get("serveCommand").get("status").asString());
        assertEquals(commandId().toString(), view.get("serveCommand").get("commandId").asString());
    }

    @Test
    void theCommandStatusReconcilesAnAppliedCommandThroughOrder() throws Exception {
        backends.failNextServe(ServeFault.LOST_ANSWER);
        serve(asWaiter(WAITER_A), order, newKey(), 4).andExpect(status().isGatewayTimeout());
        UUID command = commandId();

        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + command).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.order.deliveryStatus").value("SERVED"))
                .andExpect(jsonPath("$.reconciliation").value(nullValue()));
        assertEquals("CONFIRMED", commandStatus());
        assertEquals(1, servePosts().size(), "Reconciling is a read; nothing is resent");
        assertTrue(orderView(order.id()).get("serveCommand").isNull());
    }

    @Test
    void repeatingTheSameRequestAfterALostAnswerFindsOrdersRecordAndDoesNotResend() throws Exception {
        backends.failNextServe(ServeFault.LOST_ANSWER);
        String key = newKey();
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isGatewayTimeout());

        serve(asWaiter(WAITER_A), order, key, 4)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.order.version").value(5));
        assertEquals(1, servePosts().size());
        assertEquals(5L, backends.order(order.id()).version());
    }

    @Test
    void aCommandOrderNeverReceivedIsResentOnlyOnAnExplicitRepeatWithTheSameCommandId() throws Exception {
        backends.failNextServe(ServeFault.HANG_BEFORE_APPLY);
        String key = newKey();
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"));
        UUID command = commandId();
        assertEquals("PENDING", backends.order(order.id()).delivery());

        // Asking is a read: Order has no record, and Waiter does not conclude "not served" from that.
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + command).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.reconciliation").value("NOT_RECORDED"))
                .andExpect(jsonPath("$.order").value(nullValue()));
        assertEquals(1, servePosts().size());

        // The explicit repeat asks first, finds nothing, then sends the same command id again.
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        List<DownstreamStub.Request> posts = servePosts();
        assertEquals(2, posts.size());
        assertEquals(posts.get(0).header("Idempotency-Key"), posts.get(1).header("Idempotency-Key"));
        assertEquals(command.toString(), posts.get(1).header("Idempotency-Key"));
        assertEquals(5L, backends.order(order.id()).version(), "Order applied it exactly once");
        assertEquals(1, commandCount());
    }

    @Test
    void anUnavailableOrderLeavesTheOutcomeUnknownAndTheOrderUntouched() throws Exception {
        backends.failNextServe(ServeFault.SERVER_ERROR);
        serve(asWaiter(), order, newKey(), 4).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandStatus());
        assertEquals("PENDING", backends.order(order.id()).delivery());
    }

    @Test
    void anUnreadableAnswerAfterApplyingIsUnknownAndLaterConfirmedByOrder() throws Exception {
        backends.failNextServe(ServeFault.GARBAGE_ANSWER);
        serve(asWaiter(), order, newKey(), 4).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandStatus());
        assertEquals("SERVED", backends.order(order.id()).delivery());
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + commandId()).with(asWaiter()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void anAnswerForTheWrongRestaurantOrOrderIsNeverTrusted() throws Exception {
        STUB.override("POST", SERVE_POST, request -> {
            JsonNode command = FakeBackends.read(request.body());
            FakeBackends.Order foreign = new FakeBackends.Order(order.id(), OTHER_RESTAURANT, order.tableId(), S1, "ORD-1",
                    "ACCEPTED", "SERVED", "READY", Instant.parse("2026-10-16T09:00:00Z"), Instant.parse("2026-10-16T09:10:00Z"),
                    Instant.parse("2026-10-16T10:30:00Z"));
            return Response.json(200, FakeBackends.write(Map.of("commandId", command.get("commandId").asString(),
                    "outcome", "APPLIED", "order", FakeBackends.orderJson(foreign))));
        });
        serve(asWaiter(), order, newKey(), 4).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandStatus());
    }

    @Test
    void anAnswerThatContradictsItselfIsNotAConfirmation() throws Exception {
        STUB.override("POST", SERVE_POST, request -> {
            JsonNode command = FakeBackends.read(request.body());
            // "APPLIED" but the returned order is still pending delivery.
            return Response.json(200, FakeBackends.write(Map.of("commandId", command.get("commandId").asString(),
                    "outcome", "APPLIED", "order", FakeBackends.orderJson(order))));
        });
        serve(asWaiter(), order, newKey(), 4).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SERVE_RESULT_UNKNOWN"));
        assertEquals("UNKNOWN", commandStatus());
    }

    @Test
    void anOrderThatRefusesWaiterItselfIsADefiniteNonResultNotAnUnknownOne() throws Exception {
        backends.failNextServe(ServeFault.UNAUTHORIZED);
        serve(asWaiter(), order, newKey(), 4).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED")).andExpect(jsonPath("$.commandId").exists());
        assertEquals("REJECTED", commandStatus());
        assertEquals("PENDING", backends.order(order.id()).delivery());
        assertTrue(orderView(order.id()).get("serveCommand").isNull());
    }

    @Test
    void unusableRefusalsFromOrderNeverEchoTheirText() throws Exception {
        STUB.override("POST", SERVE_POST, request -> Response.json(409,
                "{\"code\":\"<script>alert(1)</script>\",\"detail\":\"secret-internal-host:5052\"}"));
        String response = serve(asWaiter(), order, newKey(), 4).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_REJECTED")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("script") || response.contains("secret-internal-host"));
        assertEquals("ORDER_REJECTED", commandRow().get("rejection_code"));
    }

    // ----- an attempt that is still running -----

    private UUID insertPendingCommand(FakeBackends.Order target, String key, Instant lastTouched) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_serve_commands (id, restaurant_id, order_id, table_session_id, expected_version,
                       requested_by_user_id, idempotency_key, request_digest, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 4, ?, ?, ?, 'PENDING', ?, ?)
                """, id, RESTAURANT, target.id(), S1, WAITER_A, key,
                IdempotencyKeys.digest("SERVE", target.id(), S1, 4L), Timestamp.from(lastTouched), Timestamp.from(lastTouched));
        return id;
    }

    @Test
    void aRepeatWhileTheFirstAttemptIsStillRunningIsToldToWaitNotSentTwice() throws Exception {
        String key = newKey();
        insertPendingCommand(order, key, Instant.now());
        serve(asWaiter(WAITER_A), order, key, 4)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVE_IN_PROGRESS"));
        assertTrue(STUB.requests().stream().noneMatch(r -> r.path().contains("/serve")), "Order is not asked while an attempt runs");
    }

    @Test
    void anAttemptThatDiedLongAgoIsReconciledLikeAnUnknownOne() throws Exception {
        String key = newKey();
        UUID command = insertPendingCommand(order, key, Instant.now().minusSeconds(600));
        // The process died after Order applied the command: ask Order, find the record, confirm.
        serve(asWaiter(WAITER_A), order, key, 4).andExpect(status().isOk());   // Order has no record: resend under the same id
        assertEquals(command.toString(), servePosts().getFirst().header("Idempotency-Key"));
        assertEquals("CONFIRMED", commandStatus());
        assertEquals(1, commandCount());
    }

    // ----- the command status endpoint -----

    @Test
    void theStatusOfAnotherRestaurantsOrWrongOrdersCommandLooksMissing() throws Exception {
        serveOnce(order).andExpect(status().isOk());
        UUID command = commandId();
        FakeBackends.Order other = readyOrder("ORD-2");
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + command).with(asWaiterOf(OTHER_RESTAURANT)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVE_COMMAND_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/waiters/orders/" + other.id() + "/served-commands/" + command).with(asWaiter()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + UUID.randomUUID()).with(asWaiter()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/not-a-uuid").with(asWaiter()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + command))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAnsweredCommandsStatusIsReadFromTheRecordWithoutAskingOrder() throws Exception {
        serveOnce(order).andExpect(status().isOk());
        STUB.reset();
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + commandId()).with(asWaiter()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertTrue(STUB.requests().isEmpty());
    }

    @Test
    void unknownCommandsAreFlaggedOnEveryOrderViewUntilReconciled() throws Exception {
        backends.failNextServe(ServeFault.LOST_ANSWER);
        serveOnce(order).andExpect(status().isGatewayTimeout());
        UUID command = commandId();
        mockMvc.perform(get("/api/v1/waiters/orders?view=READY_HISTORY").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].serveCommand.commandId").value(command.toString()))
                .andExpect(jsonPath("$.items[0].serveCommand.status").value("UNKNOWN"));
        mockMvc.perform(get("/api/v1/waiters/tables/" + table.id() + "/orders").with(asWaiter()))
                .andExpect(jsonPath("$.items[0].serveCommand.commandId").value(command.toString()));
        FakeBackends.Order untouched = readyOrder("ORD-2");
        mockMvc.perform(get("/api/v1/waiters/orders/" + untouched.id()).with(asWaiter()))
                .andExpect(jsonPath("$.serveCommand").value(nullValue()));
        mockMvc.perform(get("/api/v1/waiters/orders/" + order.id() + "/served-commands/" + command).with(asWaiter()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mockMvc.perform(get("/api/v1/waiters/orders?view=READY_HISTORY").with(asWaiter()))
                .andExpect(jsonPath("$.items[*].serveCommand.status", not(hasItem("UNKNOWN"))));
    }

    @Test
    void servingNeedsOnlyOrderAndNeverQrOrKitchen() throws Exception {
        serveOnce(order).andExpect(status().isOk());
        assertTrue(STUB.requests().stream().allMatch(r -> r.path().startsWith("/api/v1/internal/orders/")),
                "Only Order is involved in the command: " + STUB.requests().stream().map(DownstreamStub.Request::path).toList());
    }
}
