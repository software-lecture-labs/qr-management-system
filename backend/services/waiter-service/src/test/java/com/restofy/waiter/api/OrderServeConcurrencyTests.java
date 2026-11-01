package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Two waiters racing, and a serve racing a cancellation. The decision belongs to Order, so these tests check
 * that Waiter relays whatever single decision Order made, never invents a second outcome and records exactly
 * one answer per command. The Order stub decides inside one critical section; the real Order's atomicity is
 * not proven here (it is a requirement towards Order).
 */
class OrderServeConcurrencyTests extends ApiTestBase {

    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final int ROUNDS = 15;

    private FakeBackends.Table table;

    @BeforeEach
    void occupiedTable() {
        table = addTable("1", "OCCUPIED", S1);
    }

    private record Answer(int status, String body) {
        String code() {
            var code = FakeBackends.read(body).get("code");
            return code == null ? null : code.asString();
        }
    }

    private Callable<Answer> serve(UUID waiter, FakeBackends.Order order, String key) {
        return () -> {
            var response = mockMvc.perform(post("/api/v1/waiters/orders/" + order.id() + "/served").with(asWaiter(waiter))
                    .with(csrf().asHeader()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tableSessionId\":\"" + S1 + "\",\"expectedVersion\":" + order.version() + "}")).andReturn().getResponse();
            return new Answer(response.getStatus(), response.getContentAsString());
        };
    }

    private List<Answer> race(List<Callable<Answer>> tasks, Runnable alongside) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size() + 1);
        CountDownLatch ready = new CountDownLatch(tasks.size() + (alongside == null ? 0 : 1));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Answer>> futures = new ArrayList<>();
        for (Callable<Answer> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        Future<?> side = alongside == null ? null : pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            alongside.run();
        });
        ready.await();
        go.countDown();
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> future : futures) answers.add(future.get());
        if (side != null) side.get();
        pool.shutdown();
        return answers;
    }

    @Test
    void twoWaitersServingTheSameOrderGetExactlyOneWinnerAndTheOtherGetsOrdersRefusal() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            FakeBackends.Order order = addOrder(table, S1, "ORD-" + round, "ACCEPTED", "PENDING", "READY", "2026-10-16T09:00:00Z");
            List<Answer> answers = race(List.of(serve(WAITER_A, order, newKey()), serve(WAITER_B, order, newKey())), null);

            assertEquals(1, answers.stream().filter(a -> a.status() == 200).count(), "round " + round + ": " + answers);
            Answer loser = answers.stream().filter(a -> a.status() != 200).findFirst().orElseThrow();
            assertEquals(409, loser.status());
            assertTrue(List.of("ORDER_VERSION_CONFLICT", "ORDER_ALREADY_SERVED").contains(loser.code()), loser.body());
            assertEquals("SERVED", backends.order(order.id()).delivery());
            assertEquals(order.version() + 1, backends.order(order.id()).version(), "Order applied the transition once");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_serve_commands WHERE order_id = ? AND status = 'CONFIRMED'",
                    Integer.class, order.id()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_serve_commands WHERE order_id = ? AND status = 'REJECTED'",
                    Integer.class, order.id()));
        }
    }

    @Test
    void theSameServeRequestSentTwiceAtOnceHasOneEffect() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            FakeBackends.Order order = addOrder(table, S1, "ORD-" + round, "ACCEPTED", "PENDING", "READY", "2026-10-16T09:00:00Z");
            String key = newKey();
            List<Answer> answers = race(List.of(serve(WAITER_A, order, key), serve(WAITER_A, order, key)), null);

            // One request does the work; the other either repeats its answer or is told to wait for the running attempt.
            assertTrue(answers.stream().anyMatch(a -> a.status() == 200), "round " + round + ": " + answers);
            assertTrue(answers.stream().allMatch(a -> a.status() == 200
                    || (a.status() == 409 && "SERVE_IN_PROGRESS".equals(a.code()))), "round " + round + ": " + answers);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_serve_commands WHERE order_id = ?", Integer.class, order.id()));
            assertEquals(order.version() + 1, backends.order(order.id()).version(), "Order applied the transition once");
        }
    }

    @Test
    void aServeRacingACancellationEndsInExactlyOneOfTheTwoOutcomes() throws Exception {
        int served = 0;
        int cancelled = 0;
        for (int round = 0; round < ROUNDS; round++) {
            FakeBackends.Order order = addOrder(table, S1, "ORD-" + round, "ACCEPTED", "PENDING", "READY", "2026-10-16T09:00:00Z");
            List<Answer> answers = race(List.of(serve(WAITER_A, order, newKey())), () -> backends.cancel(order.id()));

            FakeBackends.Order finalState = backends.order(order.id());
            Answer answer = answers.getFirst();
            if (answer.status() == 200) {
                served++;
                assertEquals("SERVED", finalState.delivery(), "round " + round);
                assertEquals("ACCEPTED", finalState.business(), "A served order cannot also have been cancelled");
            } else {
                cancelled++;
                assertEquals(409, answer.status(), "round " + round + ": " + answer);
                assertTrue(List.of("ORDER_CANCELLED", "ORDER_VERSION_CONFLICT").contains(answer.code()), answer.body());
                assertEquals("CANCELLED", finalState.business());
                assertEquals("PENDING", finalState.delivery(), "A cancelled order is never served");
            }
        }
        assertEquals(ROUNDS, served + cancelled);
    }
}
