package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Concurrent callers on one order: exactly one change happens, and nothing is written twice. */
class OrderFulfillmentConcurrencyTests extends AbstractOrderStatusApiTest {

    private static final int THREADS = 8;
    private static final String A1 = DevFixtures.TOKEN_A1;

    /** Starts all callables at the same moment and collects their HTTP statuses and bodies. */
    private List<int[]> race(List<Callable<int[]>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<int[]>> futures = new ArrayList<>();
            for (Callable<int[]> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<int[]> results = new ArrayList<>();
            for (Future<int[]> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int[] result(ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        String body = response.getContentAsString();
        // second slot: 1 if the call reported a real change / APPLIED, 0 otherwise
        int applied = body.contains("\"changed\":true") || body.contains("\"outcome\":\"APPLIED\"")
                && body.contains("\"duplicate\":false") ? 1 : 0;
        return new int[]{response.getStatus(), applied};
    }

    @Test
    void theSameKitchenEventDeliveredManyTimesAtOnceIsAppliedExactlyOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var body = kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, "PREPARING");
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> result(kitchenEvent(service(KITCHEN_CREDENTIAL), body)));
        }
        List<int[]> results = race(calls);

        assertEquals(THREADS, results.stream().filter(r -> r[0] == 200).count(), "every delivery is acknowledged");
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "exactly one delivery applied it");
        assertEquals("PREPARING", fulfillmentOf(orderId));
        assertEquals(2, fulfillmentHistory(orderId).size());
        assertEquals(1, changeEvents(orderId));
        assertEquals(1, count("inbox_events"));
    }

    @Test
    void sameStaffMemberRepeatingTheSameStepAtOnceChangesItOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        long version = versionOf(orderId);
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> result(patchStatus(staff(DevFixtures.STAFF_WAITER_A), orderId, "SERVED")));
        }
        List<int[]> results = race(calls);

        assertEquals(THREADS, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "one real change, the rest are no-ops");
        assertEquals("SERVED", fulfillmentOf(orderId));
        assertEquals(version + 1, versionOf(orderId));
        assertEquals(List.of("READY>SERVED|STAFF|STAFF_API"),
                fulfillmentHistory(orderId).subList(3, fulfillmentHistory(orderId).size()));
        assertEquals(1, destinationsOfChangeTo(orderId, "SERVED").stream().filter("WAITER"::equals).count());
    }

    @Test
    void twoDifferentPeopleServingTheSameOrderAtOnceBothSucceedButOnlyOneChangesIt() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        String other = staff(registerSecondWaiter());
        String first = staff(DevFixtures.STAFF_WAITER_A);
        List<int[]> results = race(List.of(
                () -> result(patchStatus(first, orderId, "SERVED")),
                () -> result(patchStatus(other, orderId, "SERVED"))));

        assertEquals(2, results.stream().filter(r -> r[0] == 200).count(), "both reached the result they asked for");
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "but only one of them changed anything");
        assertEquals("SERVED", fulfillmentOf(orderId));
        long servedRows = fulfillmentHistory(orderId).stream().filter(h -> h.startsWith("READY>SERVED")).count();
        assertEquals(1, servedRows);
    }

    @Test
    void aStaffChangeAndAKitchenEventRacingForTheSameStepNeverDoubleApply() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var event = kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, "PREPARING");
        List<int[]> results = race(List.of(
                () -> result(patchStatus(staff(DevFixtures.STAFF_KITCHEN_A), orderId, "PREPARING")),
                () -> result(kitchenEvent(service(KITCHEN_CREDENTIAL), event))));

        // whoever gets the lock first makes the step; the other one finds it done and is acknowledged
        assertEquals(2, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(true, results.stream().anyMatch(r -> r[0] == 200 && r[1] == 1), "one of them applied it");
        assertEquals("PREPARING", fulfillmentOf(orderId));
        long preparingRows = fulfillmentHistory(orderId).stream().filter(h -> h.startsWith("RECEIVED>PREPARING"))
                .count();
        assertEquals(1, preparingRows, "the step happened exactly once, whoever got there first");
        assertEquals(1, changeEvents(orderId));
    }
    @Test
    void thePaymentEventDeliveredManyTimesAtOnceIsAppliedExactlyOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 120).andExpect(MockMvcResultMatchers
                .status().isOk());
        var body = paymentBody(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY", Instant.now()
                .minusSeconds(60));
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> result(paymentEvent(service(PAYMENT_CREDENTIAL), body)));
        }
        List<int[]> results = race(calls);

        assertEquals(THREADS, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "exactly one delivery applied it");
        assertEquals("PAID", paymentOf(orderId));
        assertEquals(3, paymentHistory(orderId).size());
        assertEquals(2, paymentChangeEvents(orderId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_type = 'PaymentOrderStatus'", Integer.class));
    }

    @Test
    void aPaidAndAFailedEventRacingOnAPendingPaymentLeaveExactlyOneOutcome() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 120).andExpect(MockMvcResultMatchers
                .status().isOk());
        var paid = paymentBody(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY",
                Instant.now().minusSeconds(60));
        var failed = paymentBody(UUID.randomUUID(), orderId, "FAILED", null, null,
                Instant.now().minusSeconds(61));
        List<int[]> results = race(List.of(
                () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), paid)),
                () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), failed))));

        // whichever got the lock first decided; the other one is stale or a conflict, never a second change
        assertEquals(1, results.stream().filter(r -> r[1] == 1).count(), "exactly one was applied");
        assertEquals(true, results.stream().allMatch(r -> r[0] == 200 || r[0] == 409));
        String finalStatus = paymentOf(orderId);
        assertEquals(true, finalStatus.equals("PAID") || finalStatus.equals("FAILED"), finalStatus);
        assertEquals(3, paymentHistory(orderId).size(), "NOT_REQUESTED>PENDING plus exactly one more change");
    }
    @Test
    void twoDifferentCollectionsRacingForOnePendingPaymentApplyExactlyOne() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 120).andExpect(MockMvcResultMatchers
                .status().isOk());
        UUID attempt = attemptOf(orderId);
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var body = paymentBodyWith(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY",
                    Instant.now().minusSeconds(60), attempt, UUID.randomUUID());
            calls.add(() -> result(paymentEvent(service(PAYMENT_CREDENTIAL), body)));
        }
        List<int[]> results = race(calls);

        assertEquals(2, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "one collection settled the order");
        assertEquals("PAID", paymentOf(orderId));
        assertEquals(3, paymentHistory(orderId).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type ="
                + " 'OrderPaymentReconciliationRequired'", Integer.class, orderId), "the other one is a possible double"
                + " charge and is recorded for reconciliation");
    }

    @Test
    void oneCollectionRacingForTwoOrdersSettlesExactlyOneOfThem() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        pay(first, "PENDING", 120).andExpect(MockMvcResultMatchers
                .status().isOk());
        pay(second, "PENDING", 120).andExpect(MockMvcResultMatchers
                .status().isOk());
        UUID capture = UUID.randomUUID();
        var forFirst = paymentBodyWith(UUID.randomUUID(), first, "PAID", SALAD_DUE, "TRY",
                Instant.now().minusSeconds(60), attemptOf(first), capture);
        var forSecond = paymentBodyWith(UUID.randomUUID(), second, "PAID", SALAD_DUE, "TRY",
                Instant.now().minusSeconds(60), attemptOf(second), capture);
        List<int[]> results = race(List.of(
                () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), forFirst)),
                () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), forSecond))));

        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "the money settled one order, not both");
        assertEquals(true, results.stream().allMatch(r -> r[0] == 200 || r[0] == 409));
        // a 409 means 'retry': the loser's retry now finds the capture taken and is recorded for reconciliation
        paymentEvent(service(PAYMENT_CREDENTIAL), forFirst);
        paymentEvent(service(PAYMENT_CREDENTIAL), forSecond);
        int paid = (paymentOf(first).equals("PAID") ? 1 : 0) + (paymentOf(second).equals("PAID") ? 1 : 0);
        assertEquals(1, paid);
        UUID loser = paymentOf(first).equals("PAID") ? second : first;
        assertEquals("PENDING", paymentOf(loser));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type ="
                + " 'OrderPaymentReconciliationRequired'", Integer.class, loser));
    }
    @Test
    void aPaidAndItsPendingRacingEndWithTheOrderPaidExactlyOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID orderId = placeSaladOrder(round == 0 ? A1 : round == 1 ? DevFixtures.TOKEN_A2 : A1);
            UUID attempt = UUID.randomUUID();
            UUID capture = UUID.randomUUID();
            var pending = paymentBodyWith(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY",
                    Instant.now().minusSeconds(60), attempt, null);
            var paid = paymentBodyWith(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY",
                    Instant.now().minusSeconds(50), attempt, capture);
            List<int[]> results = race(List.of(
                    () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), pending)),
                    () -> result(paymentEvent(service(PAYMENT_CREDENTIAL), paid))));

            // whichever got the order lock first: PAID waits and the PENDING then applies it, or the PENDING comes first
            assertEquals(2, results.stream().filter(r -> r[0] == 200).count());
            assertEquals("PAID", paymentOf(orderId));
            assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM payment_captures WHERE capture_id = ?",
                    String.class, capture));
            assertEquals(3, paymentHistory(orderId).size(), "NOT_REQUESTED>PENDING and PENDING>PAID, once each");
            assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_reconciliation_cases WHERE order_id = ?",
                    Integer.class, orderId));
        }
    }
}
