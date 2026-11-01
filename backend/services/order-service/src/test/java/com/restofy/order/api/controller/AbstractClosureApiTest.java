package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Helpers for the table-session lifecycle API (ADR-QR-001): the QR Service asks, the Order Service answers. */
abstract class AbstractClosureApiTest extends AbstractOrderStatusApiTest {

    protected static final UUID SESSION = DevFixtures.TABLE_SESSION_A1;
    protected static final UUID RESTAURANT = DevFixtures.RESTAURANT_A;
    protected static final String QR = service(InMemoryServiceIdentityAdapter.QR_CREDENTIAL);
    /** The QR Service's digest of one command; any 64 hex characters do. */
    protected static final String DIGEST = "d".repeat(64);

    protected static String preparations(UUID session) {
        return "/api/v1/internal/table-sessions/" + session + "/closure-preparations";
    }

    protected ResultActions prepareRaw(String authorization, UUID session, Object body) throws Exception {
        var request = post(preparations(session)).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected static Map<String, Object> settledBody(UUID restaurant, UUID operation, String digest) {
        Map<String, Object> body = new HashMap<>();
        body.put("restaurantId", restaurant);
        body.put("operationId", operation);
        body.put("purpose", "SETTLED");
        body.put("commandDigest", digest);
        return body;
    }

    /** A settled-close command from the QR Service for the default table session. */
    protected ResultActions prepare(UUID operation) throws Exception {
        return prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, DIGEST));
    }

    protected static Map<String, Object> idleGuest(UUID guestSessionId, Instant idleExpiresAt) {
        return Map.of("guestSessionId", guestSessionId, "idleExpiresAt", idleExpiresAt);
    }

    protected static Map<String, Object> idleBody(UUID operation, Instant requestedAt, Instant mainIdleExpiresAt,
                                                  List<Map<String, Object>> guests) {
        Map<String, Object> body = settledBody(RESTAURANT, operation, DIGEST);
        body.put("purpose", "IDLE");
        body.put("requestedAt", requestedAt);
        body.put("mainIdleExpiresAt", mainIdleExpiresAt);
        body.put("guests", guests);
        return body;
    }

    /** An idle-expiry command from the QR Service for the default table session. */
    @SafeVarargs
    protected final ResultActions idle(UUID operation, Instant mainIdleExpiresAt, Map<String, Object>... guests)
            throws Exception {
        return prepareRaw(QR, SESSION, idleBody(operation, Instant.now(), mainIdleExpiresAt, List.of(guests)));
    }

    protected ResultActions lookup(String authorization, UUID session, UUID operation, UUID restaurant)
            throws Exception {
        var request = get(preparations(session) + "/" + operation).param("restaurantId", restaurant.toString());
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected ResultActions lookup(UUID operation) throws Exception {
        return lookup(QR, SESSION, operation, RESTAURANT);
    }

    /** The question that names the digest of the exact command it is about. */
    protected ResultActions lookupCommand(UUID operation, String commandDigest) throws Exception {
        return mvc.perform(get(preparations(SESSION) + "/" + operation).param("restaurantId", RESTAURANT.toString())
                .param("commandDigest", commandDigest).header(HttpHeaders.AUTHORIZATION, QR));
    }

    /** The confirmation that names the digest of the exact command it is about. */
    protected ResultActions confirmCommand(UUID operation, String outcome, String commandDigest) throws Exception {
        Map<String, Object> body = Map.of("restaurantId", RESTAURANT, "outcome", outcome,
                "commandDigest", commandDigest);
        return mvc.perform(post(preparations(SESSION) + "/" + operation + "/confirmation")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))
                .header(HttpHeaders.AUTHORIZATION, QR));
    }

    protected ResultActions confirmRaw(String authorization, UUID session, UUID operation, UUID restaurant,
                                       String outcome, Map<String, Object> receipt) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("restaurantId", restaurant);
        body.put("outcome", outcome);
        body.put("commandDigest", DIGEST);
        if (receipt != null) {
            body.put("receipt", receipt);
        }
        var request = post(preparations(session) + "/" + operation + "/confirmation")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected ResultActions confirm(String authorization, UUID session, UUID operation, UUID restaurant,
                                    String outcome) throws Exception {
        return confirmRaw(authorization, session, operation, restaurant, outcome, null);
    }

    protected ResultActions confirm(UUID operation, String outcome) throws Exception {
        return confirmRaw(QR, SESSION, operation, RESTAURANT, outcome, null);
    }

    protected static Map<String, Object> receipt(String effect) {
        return Map.of("effect", effect, "sessionVersion", 3, "tableVersion", 7, "completedAt", Instant.now(),
                "expiredGuests", 2);
    }

    protected ResultActions orderState(String authorization, UUID session, UUID restaurant) throws Exception {
        var request = get("/api/v1/internal/table-sessions/" + session + "/order-state")
                .param("restaurantId", restaurant.toString());
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected ResultActions orderState() throws Exception {
        return orderState(QR, SESSION, RESTAURANT);
    }

    /** A guest orders a salad; the kitchen and the waiter finish it and the payment service collects the money. */
    protected UUID finishedAndPaidOrder(String guestToken) throws Exception {
        UUID orderId = placeSaladOrder(guestToken);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());
        return orderId;
    }

    protected List<String> blockersOf(ResultActions result) throws Exception {
        List<String> blockers = new ArrayList<>();
        body(result).path("blockers").forEach(b -> blockers.add(b.asString()));
        return blockers;
    }

    protected int closures() {
        return count("table_session_closures");
    }

    protected ResultActions orderAgain(String guestToken) throws Exception {
        addItem(guestToken, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        return placeOrder(guestToken, newKey(), confirmedCheckout(guestToken));
    }

    /** The guest cancels their own order; nothing has started, so it is decided at once. */
    protected void cancelAsGuest(UUID orderId, String token) throws Exception {
        cancelAll(guest(token), orderId).andExpect(status().isCreated());
    }

    /** A waiter cancels the order (also possible once it is paid; the refund it requires is only recorded). */
    protected void cancelAsWaiter(UUID orderId) throws Exception {
        cancelAll(staff(DevFixtures.STAFF_WAITER_A), orderId).andExpect(status().isCreated());
    }

    private ResultActions cancelAll(String authorization, UUID orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/cancellations")
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, authorization)
                .header("Idempotency-Key", newKey()).content(toJson(Map.of("cancelAll", true))));
    }
}
