package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.PrepareClosureRequest;
import com.restofy.order.api.dto.response.ClosureResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.TableSessionClosureService;
import com.restofy.order.domain.closure.ClosureEffect;
import com.restofy.order.domain.closure.ClosurePurpose;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001 K8: a checkout and a closure of the same table session are ordered by a database lock, so that exactly one of
 * them comes first and the other sees it. Nothing here sleeps for a fixed time: the test waits until PostgreSQL itself
 * reports a session waiting for the advisory lock, and uses latches to hold the other side.
 */
class TableSessionClosureConcurrencyTests extends AbstractOrderStatusApiTest {

    private static final UUID SESSION = DevFixtures.TABLE_SESSION_A1;
    private static final UUID RESTAURANT = DevFixtures.RESTAURANT_A;
    private static final ServiceActor QR_SERVICE = new ServiceActor(ServiceName.QR);

    @Autowired
    private TableSessionClosureStore store;
    @Autowired
    private TableSessionClosureService closures;
    @Autowired
    private PlatformTransactionManager transactions;

    private void awaitSomeoneWaitingForTheLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("nobody started waiting for the table session lock");
    }

    private void finishedAndPaidOrder() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());
    }

    @Test
    void aPreparationWaitsForACheckoutThatIsAlreadyInFlight() throws Exception {
        finishedAndPaidOrder();
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // a checkout in flight: it holds the shared lock until its transaction ends
            Future<?> checkout = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                store.lockForCheckout(RESTAURANT, SESSION);
                holding.countDown();
                await(release);
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            Future<ClosureResponse> preparation = pool.submit(() -> closures.prepare(QR_SERVICE, SESSION,
                    PrepareClosureRequest.settled(RESTAURANT, UUID.randomUUID(), "d".repeat(64))));
            awaitSomeoneWaitingForTheLock();
            assertFalse(preparation.isDone(), "the preparation must wait for the checkout in flight");
            assertEquals(0, count("table_session_closures"));

            release.countDown();
            checkout.get(10, TimeUnit.SECONDS);
            assertEquals("PREPARED", preparation.get(10, TimeUnit.SECONDS).state());
        } finally {
            release.countDown();
        }
        assertEquals(1, count("table_session_closures"));
    }

    @Test
    void severalCheckoutsOfOneSessionDoNotWaitForEachOther() throws Exception {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                store.lockForCheckout(RESTAURANT, SESSION);
                holding.countDown();
                await(release);
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            // the shared lock is granted at once although the first one still holds it
            Future<?> second = pool.submit(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(tx -> store.lockForCheckout(RESTAURANT, SESSION)));
            second.get(10, TimeUnit.SECONDS);
            assertFalse(first.isDone());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aCheckoutWaitsForAPreparationInFlightAndIsThenRefused() throws Exception {
        finishedAndPaidOrder();
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        var body = confirmedCheckout(DevFixtures.TOKEN_A2);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        UUID operation = UUID.randomUUID();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // a preparation in flight: it holds the exclusive lock and commits its PREPARED row when released
            Future<?> preparation = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                store.lockForClosure(RESTAURANT, SESSION);
                holding.countDown();
                await(release);
                store.insertPrepared(new TableSessionClosureStore.Prepared(operation, RESTAURANT, SESSION,
                        ClosurePurpose.SETTLED, ClosureEffect.SETTLED_CLOSE, "d".repeat(64), Set.of(), 1,
                        "c".repeat(64)), Instant.now());
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            Future<MockHttpServletResponse> checkout = pool.submit(() ->
                    placeOrder(DevFixtures.TOKEN_A2, newKey(), body).andReturn().getResponse());
            awaitSomeoneWaitingForTheLock();
            assertFalse(checkout.isDone(), "the checkout must wait for the preparation in flight");
            assertEquals(1, count("orders"));

            release.countDown();
            preparation.get(10, TimeUnit.SECONDS);
            MockHttpServletResponse response = checkout.get(10, TimeUnit.SECONDS);
            assertEquals(409, response.getStatus(), response.getContentAsString());
            assertEquals("TABLE_SESSION_CLOSING", json.readTree(response.getContentAsString()).path("code").asString());
        } finally {
            release.countDown();
        }
        assertEquals(1, count("orders"), "the refused order was never written");
        assertEquals(1, count("idempotency_records"), "only the first order claimed an idempotency key");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released the lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
