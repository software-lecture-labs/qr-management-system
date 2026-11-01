package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /carts/current/items with an Idempotency-Key: exact retries never add twice, and never leak. */
class CartIdempotencyApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;

    private ResultActions addWithKey(String token, String key, Object body) throws Exception {
        return mvc.perform(authorized(post(CART + "/items"), token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private int quantityOfFirstLine(String token) throws Exception {
        return json.readTree(getCart(token).andReturn().getResponse().getContentAsString())
                .path("items").get(0).path("quantity").asInt();
    }

    private int idempotencyRecords() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation = 'ADD_CART_ITEM'",
                Integer.class);
    }

    @Test
    void anExactRetryDoesNotAddTheQuantityAgain() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 2))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        long versionAfterFirst = json.readTree(getCart(A1).andReturn().getResponse().getContentAsString())
                .path("version").asLong();

        for (int retry = 0; retry < 3; retry++) {
            addWithKey(A1, key, product(DevFixtures.BURGER, 2))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.version").value(versionAfterFirst));
        }
        assertEquals(2, quantityOfFirstLine(A1));
        assertEquals(1, idempotencyRecords());
    }

    @Test
    void theSameKeyWithADifferentRequestIsAConflictAndChangesNothing() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());

        Object[] differentRequests = {
                product(DevFixtures.BURGER, 3),
                product(DevFixtures.SALAD, 2),
                product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION), List.of()),
                product(DevFixtures.BURGER, 2, List.of(), List.of(extra(DevFixtures.OPT_CHEESE, 1))),
                Map.of("productId", DevFixtures.BURGER, "quantity", 2, "note", "other"),
                Map.of("productId", DevFixtures.BURGER, "quantity", 2, "expectedCartVersion", 1)};
        for (Object different : differentRequests) {
            addWithKey(A1, key, different)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }
        assertEquals(2, quantityOfFirstLine(A1));
        assertEquals(1, count("cart_items"));
    }

    @Test
    void theSameSelectionInAnotherOrderIsTheSameRequest() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE),
                List.of(extra(DevFixtures.OPT_CHEESE, 1), extra(DevFixtures.OPT_BACON, 1)))).andExpect(status().isOk());
        addWithKey(A1, key, product(DevFixtures.BURGER, 1, List.of(DevFixtures.OPT_PICKLE, DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_BACON, 1), extra(DevFixtures.OPT_CHEESE, 1))))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertEquals(1, quantityOfFirstLine(A1));
    }

    @Test
    void concurrentRequestsWithTheSameKeyAddExactlyOnce() throws Exception {
        String key = newKey();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<Boolean>) () -> {
                start.await();
                var response = addWithKey(A1, key, product(DevFixtures.BURGER, 3)).andReturn().getResponse();
                assertEquals(200, response.getStatus(), response.getContentAsString());
                return "true".equals(response.getHeader("Idempotent-Replayed"));
            }));
        }
        start.countDown();
        int replays = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                replays++;
            }
        }
        pool.shutdown();

        assertEquals(threads - 1, replays, "exactly one request performs the addition");
        assertEquals(3, quantityOfFirstLine(A1));
        assertEquals(1, count("carts"));
        assertEquals(1, idempotencyRecords());
    }

    @Test
    void theKeyIsScopedToTheGuest() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        addWithKey(A2, key, product(DevFixtures.BURGER, 5))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertEquals(2, quantityOfFirstLine(A1));
        assertEquals(5, quantityOfFirstLine(A2));
        assertEquals(2, idempotencyRecords());
    }

    @Test
    void aRetryFromAnInactiveSessionGetsNothing() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            addWithKey(A1, key, product(DevFixtures.BURGER, 2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.items").doesNotExist())
                    .andExpect(jsonPath("$.cartId").doesNotExist());
        }
    }

    @Test
    void aRetryForACartThatWasOrderedIsNotAnsweredWithAnotherCart() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());

        addWithKey(A1, key, product(DevFixtures.SALAD, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        getCart(A1).andExpect(jsonPath("$.items.length()").value(0));
        assertEquals(1, count("orders"));
    }

    @Test
    void aRetryAfterTheCartWasClearedDoesNotAddAgain() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        clearCart(A1).andExpect(status().isOk());
        addWithKey(A1, key, product(DevFixtures.BURGER, 2))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.items.length()").value(0));
        assertEquals(0, count("cart_items"));
    }

    @Test
    void keysMustBeWellFormedWhenSentAndAreOptionalOtherwise() throws Exception {
        for (String bad : new String[]{"", "short", "has spaces in it!", "x".repeat(121), "key<script>1"}) {
            addWithKey(A1, bad, product(DevFixtures.BURGER, 1))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }
        assertEquals(0, count("carts"));

        // without a key the endpoint stays backward compatible and is simply not idempotent
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        assertEquals(2, quantityOfFirstLine(A1));
        assertEquals(0, idempotencyRecords());
    }

    @Test
    void aFailedAttemptDoesNotConsumeTheKey() throws Exception {
        String key = newKey();
        addWithKey(A1, key, product(UUID.randomUUID(), 1)).andExpect(status().isNotFound());
        assertEquals(0, idempotencyRecords());
        // the same key is free: it can be used for a corrected request, even a different one
        addWithKey(A1, key, product(DevFixtures.BURGER, 1))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertEquals(1, idempotencyRecords());
    }

    @Test
    void aFailureInsideTheTransactionRollsTheClaimBack() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 50)).andExpect(status().isOk());   // at the quantity maximum
        String key = newKey();
        addWithKey(A1, key, product(DevFixtures.BURGER, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        assertEquals(0, idempotencyRecords(), "the claim must disappear with the failed transaction");
        assertEquals(50, quantityOfFirstLine(A1));
    }

    @Test
    void theRecordKeepsADigestAndAnExpiryNotTheRequestBody() throws Exception {
        addWithKey(A1, newKey(), Map.of("productId", DevFixtures.BURGER, "quantity", 2, "note", "secret allergy note"))
                .andExpect(status().isOk());
        var row = jdbc.queryForMap("SELECT request_digest, status, created_at, expires_at FROM idempotency_records");
        assertEquals(64, row.get("request_digest").toString().length());
        assertEquals("COMPLETED", row.get("status"));
        Instant created = ((Timestamp) row.get("created_at")).toInstant();
        Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
        assertEquals(Duration.ofHours(24), Duration.between(created, expires), "cart additions are kept 24 hours");

        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'idempotency_records'", String.class);
        assertFalse(columns.stream().anyMatch(c -> c.contains("payload") || c.contains("body") || c.contains("response_body")),
                "no request/response bodies are stored: " + columns);
        assertTrue(jdbc.queryForList("SELECT request_digest FROM idempotency_records", String.class).stream()
                .noneMatch(d -> d.contains("allergy")));
    }
}
