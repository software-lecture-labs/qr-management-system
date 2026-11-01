package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCheckoutApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;

    private void fillCart(String token) throws Exception {
        addItem(token, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
    }

    // ---- T1.b: validation before ordering ------------------------------------------------------------------------

    @Test
    void idempotencyKeyIsRequiredAndMustBeWellFormed() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        for (String bad : new String[]{null, "", "short", "has spaces in it!", "x".repeat(121), "key<script>1"}) {
            placeOrder(A1, bad, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }
        assertEquals(0, count("orders"));
    }

    @Test
    void emptyOrMissingCartCannotBeOrdered() throws Exception {
        Map<String, Object> body = Map.of("expectedCartVersion", 0, "expectedTotalMinor", 0);
        placeOrder(A1, newKey(), body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        fillCart(A1);
        clearCart(A1).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        assertEquals(0, count("orders"));
    }

    @Test
    void staleCartVersionIsRejectedAndNothingIsOrdered() throws Exception {
        fillCart(A1);
        Map<String, Object> seen = confirmedCheckout(A1);
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        placeOrder(A1, newKey(), seen)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        assertEquals(0, count("orders"));
        assertEquals(0, count("outbox_events"));
    }

    @Test
    void unavailableProductsAndIngredientsBlockCheckout() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);

        menu.remove(DevFixtures.RESTAURANT_A, DevFixtures.SALAD);
        placeOrder(A1, newKey(), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.productId").value(DevFixtures.SALAD.toString()));

        menu.reset();
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, false,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), burger.options()));
        placeOrder(A1, newKey(), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));

        var retiredCheese = burger.options().stream().map(o -> o.optionId().equals(DevFixtures.OPT_CHEESE)
                ? new ProductSnapshot.ProductOption(o.optionId(), o.name(), ProductSnapshot.OptionType.ADD,
                o.priceDeltaMinor(), 0, 2, false) : o).toList();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, true,
                "TRY", burger.basePriceGrossMinor(), burger.taxRateBps(), retiredCheese));
        placeOrder(A1, newKey(), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        assertEquals(0, count("orders"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    @Test
    void missingTaxInformationBlocksCheckoutInsteadOfGuessingARate() throws Exception {
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "TRY",
                9_000L, null, List.of()));
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAX_DATA_MISSING"))
                .andExpect(jsonPath("$.details.productId").value(DevFixtures.SALAD.toString()));
        assertEquals(0, count("orders"));
    }

    @Test
    void currencyChangeOfAProductBlocksCheckout() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(DevFixtures.SALAD, "Dev Salad", true, true, "USD",
                900L, 2_000, List.of()));
        placeOrder(A1, newKey(), body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void closedOrExpiredSessionsCannotOrder() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            placeOrder(A1, newKey(), body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        }
        assertEquals(0, count("orders"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    @Test
    void malformedRequestsAndForeignFieldsAreRejected() throws Exception {
        fillCart(A1);
        placeOrder(A1, newKey(), Map.of()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        placeOrder(A1, newKey(), Map.of("expectedCartVersion", -1, "expectedTotalMinor", 5))
                .andExpect(status().isUnprocessableContent());
        placeOrder(A1, newKey(), Map.of("expectedCartVersion", 1, "expectedTotalMinor", 5, "note", "x".repeat(301)))
                .andExpect(status().isUnprocessableContent());
        for (String foreign : new String[]{"\"restaurantId\": \"" + UUID.randomUUID() + "\"",
                "\"tableId\": \"" + UUID.randomUUID() + "\"", "\"tableSessionId\": \"" + UUID.randomUUID() + "\"",
                "\"cartId\": \"" + UUID.randomUUID() + "\"", "\"totalMinor\": 1", "\"items\": []"}) {
            mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders")
                            .header("Authorization", "Bearer " + A1).header("Idempotency-Key", newKey())
                            .contentType("application/json")
                            .content("{\"expectedCartVersion\": 1, \"expectedTotalMinor\": 5, " + foreign + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertEquals(0, count("orders"));
    }

    @Test
    void anonymousCallersCannotOrder() throws Exception {
        placeOrder(null, newKey(), Map.of("expectedCartVersion", 0, "expectedTotalMinor", 0))
                .andExpect(status().isUnauthorized());
        placeOrder("unknown-token", newKey(), Map.of("expectedCartVersion", 0, "expectedTotalMinor", 0))
                .andExpect(status().isUnauthorized());
    }

    // ---- T1.c / T1.d: atomic creation, idempotency, concurrency ------------------------------------------------------

    @Test
    void checkoutCreatesTheOrderAndSnapshotsAtomically() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();

        placeOrder(A1, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.orderNo").value("ORD-000001"))
                .andExpect(jsonPath("$.tableSessionId").value(DevFixtures.TABLE_SESSION_A1.toString()))
                .andExpect(jsonPath("$.fulfillmentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                // burger 25000 + cheese 2500 = 27500 x2 = 55000 ; salad 9000 -> 64000
                .andExpect(jsonPath("$.amounts.grossMinor").value(64_000))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name").value("Dev Burger"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].removedIngredients[0].name").value("Onion"))
                .andExpect(jsonPath("$.items[0].extras[0].name").value("Extra cheese"))
                .andExpect(jsonPath("$.placedAt").isNotEmpty())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertEquals(1, count("orders"));
        assertEquals(2, count("order_items"));
        assertEquals(2, count("order_item_options"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts", String.class));
        assertEquals(1, count("idempotency_records"));
        assertEquals(1, count("outbox_events"));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id WHERE e.event_type = 'OrderPlaced' AND d.status = 'PENDING'", Integer.class));

        // the ordered cart is gone for the guest; the next cart starts empty
        getCart(A1).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void doubleClickWithTheSameKeyReturnsTheSameOrder() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();

        JsonNode first = json.readTree(placeOrder(A1, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        JsonNode second = json.readTree(placeOrder(A1, key, body).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString());

        assertEquals(first.path("orderId").asString(), second.path("orderId").asString());
        assertEquals(first.path("orderNo").asString(), second.path("orderNo").asString());
        assertEquals(first.path("amounts").path("grossMinor").asLong(), second.path("amounts").path("grossMinor").asLong());
        assertEquals(1, count("orders"));
        assertEquals(1, count("outbox_events"));
        assertEquals(1, count("idempotency_records"));
    }

    @Test
    void replayStillWorksAfterTheCartChangedButNeverToAnInactiveSession() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        String orderId = json.readTree(placeOrder(A1, key, body).andReturn().getResponse().getContentAsString())
                .path("orderId").asString();

        addItem(A1, product(DevFixtures.SALAD, 3)).andExpect(status().isOk());
        placeOrder(A1, key, body).andExpect(status().isCreated()).andExpect(jsonPath("$.orderId").value(orderId));

        // once the session is closed or expired the previous response is no longer handed out
        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            placeOrder(A1, key, body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.orderId").doesNotExist())
                    .andExpect(jsonPath("$.orderNo").doesNotExist());
        }
        assertEquals(1, count("orders"));
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflict() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        placeOrder(A1, key, body).andExpect(status().isCreated());

        placeOrder(A1, key, Map.of("expectedCartVersion", body.get("expectedCartVersion"),
                "expectedTotalMinor", body.get("expectedTotalMinor"), "note", "different"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        placeOrder(A1, key, Map.of("expectedCartVersion", body.get("expectedCartVersion"),
                "expectedTotalMinor", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count("orders"));
    }

    @Test
    void theSameKeyFromAnotherGuestIsAnIndependentCommand() throws Exception {
        fillCart(A1);
        fillCart(A2);
        String key = newKey();
        String first = json.readTree(placeOrder(A1, key, confirmedCheckout(A1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("orderId").asString();
        String second = json.readTree(placeOrder(A2, key, confirmedCheckout(A2)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("orderId").asString();
        assertNotEquals(first, second);
        assertEquals(2, count("orders"));
    }

    @Test
    void anotherKeyCannotOrderTheSameCartAgain() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        placeOrder(A1, newKey(), body).andExpect(status().isCreated());
        placeOrder(A1, newKey(), body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        assertEquals(1, count("orders"));
    }

    @Test
    void concurrentRequestsWithTheSameKeyProduceExactlyOneOrder() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                var response = placeOrder(A1, key, body).andReturn().getResponse();
                assertEquals(201, response.getStatus(), response.getContentAsString());
                return json.readTree(response.getContentAsString()).path("orderId").asString();
            }));
        }
        start.countDown();
        List<String> orderIds = new ArrayList<>();
        for (Future<String> result : results) {
            orderIds.add(result.get());
        }
        pool.shutdown();

        assertEquals(1, orderIds.stream().distinct().count(), "every duplicate must return the same order");
        assertEquals(1, count("orders"));
        assertEquals(1, count("outbox_events"));
        assertEquals(1, count("idempotency_records"));
    }

    @Test
    void concurrentRequestsWithDifferentKeysOnOneCartProduceExactlyOneOrder() throws Exception {
        fillCart(A1);
        Map<String, Object> body = confirmedCheckout(A1);
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<Integer>) () -> {
                start.await();
                return placeOrder(A1, newKey(), body).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Integer> result : results) {
            int status = result.get();
            if (status == 201) {
                created++;
            } else {
                assertTrue(status == 409 || status == 422, "loser must get a clean 409/422 but got " + status);
            }
        }
        pool.shutdown();

        assertEquals(1, created);
        assertEquals(1, count("orders"));
        assertEquals(1, count("outbox_events"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    @Test
    void successfulCheckoutLeavesOtherGuestsCartsUntouched() throws Exception {
        fillCart(A1);
        fillCart(A2);
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        getCart(A2).andExpect(jsonPath("$.items", hasSize(2)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts WHERE guest_session_id = ?",
                String.class, DevFixtures.GUEST_A2));
    }

    @Test
    void orderNumberAppearsInTheResponseWithTheDocumentedFormat() throws Exception {
        fillCart(A1);
        placeOrder(A1, newKey(), confirmedCheckout(A1))
                .andExpect(jsonPath("$.orderNo", matchesPattern("ORD-\\d{6,}")));
    }
}
