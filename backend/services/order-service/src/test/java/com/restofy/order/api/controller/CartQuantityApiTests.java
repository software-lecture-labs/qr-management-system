package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartQuantityApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final int MAX = 50; // restofy.cart.max-item-quantity

    @Test
    void increasesAndDecreasesQuantityAndRecomputesTotals() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 2);

        patchItem(A1, lineId, Map.of("quantity", 5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.totals.grossMinor").value(125_000));
        patchItem(A1, lineId, Map.of("quantity", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.totals.grossMinor").value(25_000));
    }

    @Test
    void quantityBelowOneIsRejectedWith422AndNeverDeletesTheLine() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 2);

        for (int invalid : new int[]{0, -1, -50}) {
            patchItem(A1, lineId, Map.of("quantity", invalid))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        getCart(A1)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void quantityMustBeAnIntegerPresentAndWithinTheConfiguredMaximum() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 2);

        patchItem(A1, lineId, Map.of("quantity", MAX + 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.maxQuantity").value(MAX));
        patchItem(A1, lineId, Map.of("quantity", MAX)).andExpect(status().isOk());

        for (String raw : List.of("{\"quantity\": 1.5}", "{\"quantity\": \"3\"}", "{\"quantity\": 99999999999999}",
                "{\"quantity\": 2147483648}", "{\"quantity\": null}", "{}")) {
            mvc.perform(MockMvcRequestBuilders
                            .patch(CART + "/items/" + lineId).header("Authorization", "Bearer " + A1)
                            .contentType("application/json").content(raw))
                    .andExpect(result -> assertTrue(
                            List.of(400, 422).contains(result.getResponse().getStatus()),
                            "payload " + raw + " must be rejected, was " + result.getResponse().getStatus()));
        }
        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(MAX));
    }

    @Test
    void addingMoreThanTheMaximumIsRejectedIncludingMergedQuantity() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, MAX + 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        addItem(A1, product(DevFixtures.BURGER, MAX)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(MAX));
    }

    @Test
    void unknownLineAndOtherGuestsLineAreNotFound() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 2);

        patchItem(A1, UUID.randomUUID(), Map.of("quantity", 3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        patchItem(A2, lineId, Map.of("quantity", 3)).andExpect(status().isNotFound());
        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void expectedCartVersionMustMatchAndEveryRealChangeAdvancesIt() throws Exception {
        String body = addItem(A1, product(DevFixtures.BURGER, 2)).andReturn().getResponse().getContentAsString();
        long version = json.readTree(body).path("version").asLong();
        String lineId = json.readTree(body).path("items").get(0).path("cartItemId").asString();

        patchItem(A1, lineId, Map.of("quantity", 3, "expectedCartVersion", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1));
        // stale version: rejected, nothing changes
        patchItem(A1, lineId, Map.of("quantity", 4, "expectedCartVersion", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.currentVersion").value(version + 1));
        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void settingTheSameQuantityIsANoOpThatKeepsTheVersion() throws Exception {
        String body = addItem(A1, product(DevFixtures.BURGER, 2)).andReturn().getResponse().getContentAsString();
        long version = json.readTree(body).path("version").asLong();
        String lineId = json.readTree(body).path("items").get(0).path("cartItemId").asString();

        patchItem(A1, lineId, Map.of("quantity", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version));
    }

    @Test
    void staleVersionIsAlsoCheckedWhenAddingAndRemoving() throws Exception {
        String body = addItem(A1, product(DevFixtures.BURGER, 1)).andReturn().getResponse().getContentAsString();
        long version = json.readTree(body).path("version").asLong();
        String lineId = json.readTree(body).path("items").get(0).path("cartItemId").asString();

        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "expectedCartVersion", version + 7))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        mvc.perform(MockMvcRequestBuilders
                        .delete(CART + "/items/" + lineId + "?expectedCartVersion=" + (version + 7))
                        .header("Authorization", "Bearer " + A1))
                .andExpect(status().isConflict());
        getCart(A1).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void concurrentUpdatesOfOneLineNeverLoseOrCorruptState() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 1);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int quantity = 2 + i;
            results.add(pool.submit((Callable<Integer>) () -> {
                start.await();
                return patchItem(A1, lineId, Map.of("quantity", quantity)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertEquals(200, result.get(), "serialized updates must all succeed");
        }
        pool.shutdown();

        int finalQuantity = jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class);
        assertTrue(finalQuantity >= 2 && finalQuantity < 2 + threads, "final quantity is one of the written values");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
        assertEquals(threads + 1L, jdbc.queryForObject("SELECT version FROM carts", Long.class),
                "every distinct write advanced the cart version exactly once");
    }

    @Test
    void concurrentVersionGuardedUpdatesLetExactlyOneWin() throws Exception {
        String body = addItem(A1, product(DevFixtures.BURGER, 1)).andReturn().getResponse().getContentAsString();
        long version = json.readTree(body).path("version").asLong();
        String lineId = json.readTree(body).path("items").get(0).path("cartItemId").asString();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int quantity = 2 + i;
            results.add(pool.submit((Callable<Integer>) () -> {
                start.await();
                return patchItem(A1, lineId, Map.of("quantity", quantity, "expectedCartVersion", version))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflicts = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 200) ok++;
            else if (code == 409) conflicts++;
        }
        pool.shutdown();
        assertEquals(1, ok, "exactly one writer may use the version");
        assertEquals(threads - 1, conflicts);
        assertEquals(version + 1, jdbc.queryForObject("SELECT version FROM carts", Long.class));
    }

    @Test
    void concurrentFirstAddsCreateOneCartAndMergeQuantities() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<Integer>) () -> {
                start.await();
                return addItem(A1, product(DevFixtures.SALAD, 2)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertEquals(200, result.get());
        }
        pool.shutdown();

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class), "one cart per guest");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class), "one merged line");
        assertEquals(20, jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class));
    }

    @Test
    void concurrentGuestsDoNotInterfereWithEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = pool.submit((Callable<Integer>) () -> {
            start.await();
            return addItem(A1, product(DevFixtures.BURGER, 3)).andReturn().getResponse().getStatus();
        });
        Future<Integer> second = pool.submit((Callable<Integer>) () -> {
            start.await();
            return addItem(A2, product(DevFixtures.BURGER, 4)).andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertEquals(200, first.get());
        assertEquals(200, second.get());
        pool.shutdown();

        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(3));
        getCart(A2).andExpect(jsonPath("$.items[0].quantity").value(4));
    }
}
