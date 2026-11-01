package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real races against the real PostgreSQL: the version column, the unique indexes and the unique idempotency
 * key decide who wins, so the outcome is correct no matter how the requests interleave. Every scenario is
 * repeated with fresh calls to give the interleavings a chance to differ.
 */
class CallConcurrencyTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final int ROUNDS = 12;

    private FakeBackends.Table table;
    private ExecutorService pool;

    @BeforeEach
    void occupiedTable() {
        table = addTable("1", "OCCUPIED", SESSION);
        pool = Executors.newFixedThreadPool(6);
    }

    private record Answer(int status, String body) {
        String code() {
            return FakeBackends.read(body).get("code").asString();
        }
    }

    /** Starts all tasks together and returns their answers in task order. */
    private List<Answer> race(List<Callable<Answer>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Answer>> futures = new ArrayList<>();
        for (Callable<Answer> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> future : futures) answers.add(future.get());
        pool.shutdown();
        return answers;
    }

    private Callable<Answer> staff(UUID waiter, UUID call, String action, String key, long expectedVersion) {
        return () -> {
            var response = staffCommand(asWaiter(waiter), call, action, key, expectedVersion).andReturn().getResponse();
            return new Answer(response.getStatus(), response.getContentAsString());
        };
    }

    private Callable<Answer> guest(FakeBackends.Guest guest, String key, String body) {
        return () -> {
            var response = mockMvc.perform(post("/api/v1/waiters/calls").header("X-Guest-Token", guest.token())
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            return new Answer(response.getStatus(), response.getContentAsString());
        };
    }

    private UUID raise(String type) throws Exception {
        String body = raiseCall(addGuest(table), type).andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(FakeBackends.read(body).get("id").asString());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void twoWaitersResolvingTheSameCallWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        pool.shutdown();
        for (int round = 0; round < ROUNDS; round++) {
            pool = Executors.newFixedThreadPool(2);
            UUID call = raise("BILL");
            List<Answer> answers = race(List.of(staff(WAITER_A, call, "resolve", newKey(), 0),
                    staff(WAITER_B, call, "resolve", newKey(), 0)));

            List<Answer> winners = answers.stream().filter(a -> a.status() == 200).toList();
            List<Answer> losers = answers.stream().filter(a -> a.status() == 409).toList();
            assertEquals(1, winners.size(), "round " + round + ": " + answers);
            assertEquals(1, losers.size(), "round " + round + ": " + answers);
            assertTrue(List.of("VERSION_CONFLICT", "CALL_ALREADY_RESOLVED").contains(losers.getFirst().code()));

            // The recorded actor is the winner's; the loser neither changed the actor nor the time.
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM service_requests WHERE id = ?", call);
            UUID resolver = (UUID) row.get("resolved_by_user_id");
            assertEquals(FakeBackends.read(winners.getFirst().body()).get("resolvedByUserId").asString(), resolver.toString());
            assertEquals(1L, row.get("version"));
            assertEquals(1, count("SELECT count(*) FROM service_call_events WHERE service_request_id = ? AND event_type = 'RESOLVED'",
                    call));
            assertEquals(2, count("SELECT count(*) FROM service_call_events WHERE service_request_id = ?", call));
        }
    }

    @Test
    void aClaimRacingAResolveEndsInAConsistentStateWithOneEventPerAcceptedCommand() throws Exception {
        pool.shutdown();
        for (int round = 0; round < ROUNDS; round++) {
            pool = Executors.newFixedThreadPool(2);
            UUID call = raise("WAITER");
            List<Answer> answers = race(List.of(staff(WAITER_A, call, "claim", newKey(), 0),
                    staff(WAITER_B, call, "resolve", newKey(), 0)));

            long accepted = answers.stream().filter(a -> a.status() == 200).count();
            assertEquals(1, accepted, "Both commands started from version 0, so exactly one may apply: " + answers);
            assertEquals(1, answers.stream().filter(a -> a.status() == 409).count());
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM service_requests WHERE id = ?", call);
            assertEquals(1L, row.get("version"));
            assertTrue(List.of("CLAIMED", "RESOLVED").contains(row.get("status")));
            assertEquals(1 + accepted, count("SELECT count(*) FROM service_call_events WHERE service_request_id = ?", call));
        }
    }

    @Test
    void theSameResolveSentTwiceAtOnceHasOneEffectAndBothCallersSeeSuccess() throws Exception {
        pool.shutdown();
        for (int round = 0; round < ROUNDS; round++) {
            pool = Executors.newFixedThreadPool(3);
            UUID call = raise("HELP");
            String key = newKey();
            List<Answer> answers = race(List.of(staff(WAITER_A, call, "resolve", key, 0),
                    staff(WAITER_A, call, "resolve", key, 0), staff(WAITER_A, call, "resolve", key, 0)));

            assertTrue(answers.stream().allMatch(a -> a.status() == 200), "round " + round + ": " + answers);
            assertEquals(1, count("SELECT count(*) FROM service_call_events WHERE service_request_id = ? AND event_type = 'RESOLVED'",
                    call));
            assertEquals(1, count("SELECT count(*) FROM idempotency_records WHERE service_request_id = ? AND operation = 'RESOLVE'", call));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM service_requests WHERE id = ?", Long.class, call));
        }
    }

    @Test
    void sameGuestSameTypeAtOnceCreatesOneCallAndTheOthersGetItBack() throws Exception {
        pool.shutdown();
        for (int round = 0; round < ROUNDS; round++) {
            pool = Executors.newFixedThreadPool(4);
            jdbc.update("DELETE FROM idempotency_records");
            jdbc.update("DELETE FROM notification_deliveries");
            jdbc.update("DELETE FROM service_call_events");
            jdbc.update("DELETE FROM service_requests");
            FakeBackends.Guest guest = addGuest(table);
            List<Answer> answers = race(List.of(guest(guest, newKey(), "{\"type\":\"WAITER\"}"),
                    guest(guest, newKey(), "{\"type\":\"WAITER\"}"), guest(guest, newKey(), "{\"type\":\"WAITER\"}"),
                    guest(guest, newKey(), "{\"type\":\"WAITER\"}")));

            assertEquals(1, answers.stream().filter(a -> a.status() == 201).count(), "round " + round + ": " + answers);
            assertEquals(3, answers.stream().filter(a -> a.status() == 200).count(), "round " + round + ": " + answers);
            assertEquals(1, count("SELECT count(*) FROM service_requests"));
            assertEquals(1, count("SELECT count(*) FROM service_call_events"));
            assertEquals(1, answers.stream().map(a -> FakeBackends.read(a.body()).get("id").asString()).distinct().count());
        }
    }

    @Test
    void theSameCreateSentTwiceAtOnceIsOneCallWithOneSharedAnswer() throws Exception {
        pool.shutdown();
        for (int round = 0; round < ROUNDS; round++) {
            pool = Executors.newFixedThreadPool(3);
            jdbc.update("DELETE FROM idempotency_records");
            jdbc.update("DELETE FROM notification_deliveries");
            jdbc.update("DELETE FROM service_call_events");
            jdbc.update("DELETE FROM service_requests");
            FakeBackends.Guest guest = addGuest(table);
            String key = newKey();
            List<Answer> answers = race(List.of(guest(guest, key, "{\"type\":\"BILL\"}"),
                    guest(guest, key, "{\"type\":\"BILL\"}"), guest(guest, key, "{\"type\":\"BILL\"}")));

            assertTrue(answers.stream().allMatch(a -> a.status() == 201), "round " + round + ": " + answers);
            assertEquals(1, count("SELECT count(*) FROM service_requests"));
            assertEquals(1, count("SELECT count(*) FROM service_call_events"));
            assertEquals(1, answers.stream().map(a -> FakeBackends.read(a.body()).get("id").asString()).distinct().count());
        }
    }

    @Test
    void manyGuestsAtTheSameTableAllGetTheirOwnCall() throws Exception {
        List<Callable<Answer>> tasks = new ArrayList<>();
        for (int index = 0; index < 6; index++) tasks.add(guest(addGuest(table), newKey(), "{\"type\":\"BILL\"}"));
        List<Answer> answers = race(tasks);
        assertTrue(answers.stream().allMatch(a -> a.status() == 201), answers.toString());
        assertEquals(6, count("SELECT count(*) FROM service_requests"));
        assertEquals(6, count("SELECT count(DISTINCT guest_session_id) FROM service_requests"));
        assertEquals(6, count("SELECT count(*) FROM service_call_events"));
        assertEquals(6, count("SELECT count(*) FROM notification_deliveries"));
    }
}
