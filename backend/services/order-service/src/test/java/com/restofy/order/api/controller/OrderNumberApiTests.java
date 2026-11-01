package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class OrderNumberApiTests extends AbstractCartApiTest {

    /** Registers a fresh guest at the fixture table of the given restaurant and returns its token. */
    private String newGuest(String token, UUID restaurant, UUID table, UUID tableSession) {
        qr.register(token, new GuestSessionContext(restaurant, table, tableSession, UUID.randomUUID(),
                SessionState.ACTIVE));
        return token;
    }

    private JsonNode order(String token) throws Exception {
        addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        return json.readTree(placeOrder(token, newKey(), confirmedCheckout(token)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void numbersAreSequentialPerRestaurantAndIndependentAcrossRestaurants() throws Exception {
        assertEquals("ORD-000001", order(DevFixtures.TOKEN_A1).path("orderNo").asString());
        assertEquals("ORD-000002", order(DevFixtures.TOKEN_A2).path("orderNo").asString());
        assertEquals("ORD-000003", order(DevFixtures.TOKEN_A1).path("orderNo").asString());
        // restaurant B has its own counter
        assertEquals("ORD-000001", order(DevFixtures.TOKEN_B1).path("orderNo").asString());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_number_counters", Integer.class));
    }

    @Test
    void orderIdAndOrderNumberAreDistinctIdentifiers() throws Exception {
        JsonNode first = order(DevFixtures.TOKEN_A1);
        JsonNode second = order(DevFixtures.TOKEN_A1);
        assertNotEquals(first.path("orderId").asString(), second.path("orderId").asString());
        UUID.fromString(first.path("orderId").asString());   // machine id is a UUID
        assertTrue(first.path("orderNo").asString().matches("ORD-\\d{6,}")); // display id is not
    }

    @Test
    void concurrentCheckoutsGetUniqueGaplessNumbers() throws Exception {
        int guests = 24;
        List<String> tokens = IntStream.range(0, guests).mapToObj(i -> newGuest("concurrent-a-" + i,
                DevFixtures.RESTAURANT_A, DevFixtures.TABLE_A1, DevFixtures.TABLE_SESSION_A1)).toList();
        // each guest fills a cart first so the contended part is only the checkout itself
        for (String token : tokens) {
            addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        }
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (String token : tokens) {
            bodies.add(confirmedCheckout(token));
        }

        ExecutorService pool = Executors.newFixedThreadPool(guests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < guests; i++) {
            String token = tokens.get(i);
            Map<String, Object> body = bodies.get(i);
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                var response = placeOrder(token, newKey(), body).andReturn().getResponse();
                assertEquals(201, response.getStatus(), response.getContentAsString());
                return json.readTree(response.getContentAsString()).path("orderNo").asString();
            }));
        }
        start.countDown();
        Set<String> numbers = new HashSet<>();
        for (Future<String> result : results) {
            assertTrue(numbers.add(result.get()), "order numbers must be unique");
        }
        pool.shutdown();

        Set<String> expected = IntStream.rangeClosed(1, guests).mapToObj(i -> String.format("ORD-%06d", i))
                .collect(Collectors.toSet());
        assertEquals(expected, numbers, "numbers are exactly 1..N: unique and without gaps");
        assertEquals(guests, jdbc.queryForObject("SELECT count(DISTINCT order_no) FROM orders", Integer.class));
        assertEquals(guests, jdbc.queryForObject("SELECT last_value FROM order_number_counters", Integer.class));
    }

    @Test
    void concurrentCheckoutsInTwoRestaurantsKeepIndependentSequences() throws Exception {
        int perRestaurant = 8;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < perRestaurant; i++) {
            tokens.add(newGuest("mix-a-" + i, DevFixtures.RESTAURANT_A, DevFixtures.TABLE_A1, DevFixtures.TABLE_SESSION_A1));
            tokens.add(newGuest("mix-b-" + i, DevFixtures.RESTAURANT_B, DevFixtures.TABLE_B1, DevFixtures.TABLE_SESSION_B1));
        }
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (String token : tokens) {
            addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
            bodies.add(confirmedCheckout(token));
        }
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            Map<String, Object> body = bodies.get(i);
            results.add(pool.submit((Callable<Integer>) () -> {
                start.await();
                return placeOrder(token, newKey(), body).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertEquals(201, result.get());
        }
        pool.shutdown();

        for (UUID restaurant : List.of(DevFixtures.RESTAURANT_A, DevFixtures.RESTAURANT_B)) {
            List<String> numbers = jdbc.queryForList("SELECT order_no FROM orders WHERE restaurant_id = ? ORDER BY order_no",
                    String.class, restaurant);
            assertEquals(IntStream.rangeClosed(1, perRestaurant).mapToObj(i -> String.format("ORD-%06d", i)).toList(),
                    numbers);
        }
    }

    @Test
    void failedAndReplayedCheckoutsDoNotConsumeNumbers() throws Exception {
        addItem(DevFixtures.TOKEN_A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(DevFixtures.TOKEN_A1);

        // rejected before the transaction: stale total
        placeOrder(DevFixtures.TOKEN_A1, newKey(), Map.of("expectedCartVersion", body.get("expectedCartVersion"),
                "expectedTotalMinor", 1)).andExpect(status().isConflict());
        String key = newKey();
        placeOrder(DevFixtures.TOKEN_A1, key, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("ORD-000001"));
        placeOrder(DevFixtures.TOKEN_A1, key, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("ORD-000001"));

        assertEquals("ORD-000002", order(DevFixtures.TOKEN_A2).path("orderNo").asString());
    }

    @Test
    void orderNumberAndCorrelationIdAreLoggedTogetherWithoutSensitiveData(CapturedOutput output) throws Exception {
        addItem(DevFixtures.TOKEN_A1, product(DevFixtures.SALAD, 1, List.of(), List.of())).andExpect(status().isOk());
        placeOrder(DevFixtures.TOKEN_A1, newKey(), confirmedCheckout(DevFixtures.TOKEN_A1))
                .andExpect(status().isCreated());

        String line = output.getAll().lines().filter(l -> l.contains("Order placed:")).findFirst().orElseThrow();
        assertTrue(line.contains("orderNo=ORD-000001"), line);
        assertTrue(line.matches(".*\\[[0-9a-f-]{36}].*"), "log line carries the correlation id: " + line);
        assertTrue(!output.getAll().contains(DevFixtures.TOKEN_A1), "guest tokens must never be logged");
    }

    @Test
    void orderNumberIsPartOfTheOutboxEventAndMatchesTheOrder() throws Exception {
        JsonNode order = order(DevFixtures.TOKEN_A1);
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE event_type = 'OrderPlaced'", String.class);
        JsonNode event = json.readTree(payload);
        assertEquals(order.path("orderNo").asString(), event.path("orderNo").asString());
        assertEquals(order.path("orderId").asString(), event.path("orderId").asString());
    }

    @Test
    void concurrentRetriesOfOneCheckoutConsumeExactlyOneNumber() throws Exception {
        addItem(DevFixtures.TOKEN_A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(DevFixtures.TOKEN_A1);
        String key = newKey();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                var response = placeOrder(DevFixtures.TOKEN_A1, key, body).andReturn().getResponse();
                assertEquals(201, response.getStatus(), response.getContentAsString());
                return json.readTree(response.getContentAsString()).path("orderNo").asString();
            }));
        }
        start.countDown();
        Set<String> numbers = new HashSet<>();
        for (Future<String> result : results) {
            numbers.add(result.get());
        }
        pool.shutdown();

        assertEquals(Set.of("ORD-000001"), numbers, "every retry sees the one order number");
        assertEquals(1, jdbc.queryForObject("SELECT last_value FROM order_number_counters", Integer.class),
                "retries must not burn extra numbers");
        // the next, different order gets the very next number: no gap was left behind by the retries
        assertEquals("ORD-000002", order(DevFixtures.TOKEN_A2).path("orderNo").asString());
    }

    /**
     * Measures, does not assert on speed. The per-restaurant counter row serializes allocation, so contention is
     * expected to show up as latency; this prints the observed numbers (local PostgreSQL in Docker, mock adapters,
     * MockMvc) so the claim "short queueing" rests on a measurement. It is not a load test and says nothing about
     * production capacity.
     */
    @Test
    void counterContentionIsMeasuredNotAssumed() throws Exception {
        int guests = 40;
        List<String> tokens = IntStream.range(0, guests).mapToObj(i -> newGuest("measure-" + i,
                DevFixtures.RESTAURANT_A, DevFixtures.TABLE_A1, DevFixtures.TABLE_SESSION_A1)).toList();
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (String token : tokens) {
            addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
            bodies.add(confirmedCheckout(token));
        }
        ExecutorService pool = Executors.newFixedThreadPool(guests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> latencies = new ArrayList<>();
        for (int i = 0; i < guests; i++) {
            String token = tokens.get(i);
            Map<String, Object> body = bodies.get(i);
            latencies.add(pool.submit((Callable<Long>) () -> {
                start.await();
                long begin = System.nanoTime();
                var response = placeOrder(token, newKey(), body).andReturn().getResponse();
                assertEquals(201, response.getStatus(), response.getContentAsString());
                return (System.nanoTime() - begin) / 1_000_000;
            }));
        }
        long wallStart = System.nanoTime();
        start.countDown();
        List<Long> millis = new ArrayList<>();
        for (Future<Long> latency : latencies) {
            millis.add(latency.get());
        }
        long wall = (System.nanoTime() - wallStart) / 1_000_000;
        pool.shutdown();
        millis.sort(Long::compare);

        System.out.printf("ORDER_NUMBER_CONTENTION guests=%d wall=%dms p50=%dms p95=%dms max=%dms%n", guests, wall,
                millis.get(guests / 2), millis.get((int) (guests * 0.95) - 1), millis.get(guests - 1));
        assertEquals(guests, jdbc.queryForObject("SELECT count(DISTINCT order_no) FROM orders", Integer.class));
    }
}
