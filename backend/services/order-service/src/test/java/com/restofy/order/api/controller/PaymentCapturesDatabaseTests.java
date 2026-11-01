package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore.Capture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K-14: what the DATABASE itself guarantees about collections, independent of the application code. */
class PaymentCapturesDatabaseTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Autowired
    private PaymentCaptureStore store;
    @Autowired
    private PlatformTransactionManager transactions;

    private void insertCapture(UUID captureId, UUID orderId, String status, String reason) {
        jdbc.update("INSERT INTO payment_captures (id, capture_id, restaurant_id, order_id, payment_attempt_id,"
                        + " amount_minor, currency, status, not_applied_reason, source_event_id, occurred_at, received_at,"
                        + " resolved_at) VALUES (?, ?, ?, ?, ?, 9000, 'TRY', ?, ?, ?, now(), now(), ?)",
                UUID.randomUUID(), captureId, DevFixtures.RESTAURANT_A, orderId, UUID.randomUUID(), status, reason,
                UUID.randomUUID(), status.equals("PENDING_VERIFICATION") ? null : new Timestamp(System.currentTimeMillis()));
    }

    private void markPaid(UUID orderId) {
        jdbc.update("UPDATE orders SET payment_status = 'PENDING' WHERE id = ?", orderId);
        jdbc.update("UPDATE orders SET payment_status = 'PAID', current_due_minor = 0 WHERE id = ?", orderId);
    }

    private static void refuses(Runnable statement, String message) {
        DataAccessException error = assertThrows(DataAccessException.class, statement::run);
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test
    void aCaptureIdExistsOnceAndAnOrderIsSettledByAtMostOneCollection() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        markPaid(first);
        markPaid(second);
        UUID capture = UUID.randomUUID();
        insertCapture(capture, first, "APPLIED", null);

        refuses(() -> insertCapture(capture, second, "APPLIED", null), "uq_payment_captures_capture");
        refuses(() -> insertCapture(UUID.randomUUID(), first, "APPLIED", null),
                "uq_payment_captures_one_applied_per_order");
        // a second, unapplied collection of the same order is allowed: it is the record of a possible double charge
        insertCapture(UUID.randomUUID(), first, "NOT_APPLIED", "SECOND_CAPTURE");
        assertEquals(2, count("payment_captures"));
    }

    @Test
    void anAppliedCaptureNeedsAPaidOrder() throws Exception {
        UUID unpaid = placeSaladOrder(A1);
        refuses(() -> insertCapture(UUID.randomUUID(), unpaid, "APPLIED", null), "is not PAID");
        assertEquals(0, count("payment_captures"));
    }

    @Test
    void theStatusColumnsAreConsistentAndRestaurantAndOrderMustMatch() throws Exception {
        UUID order = placeSaladOrder(A1);
        refuses(() -> insertCapture(UUID.randomUUID(), order, "NOT_APPLIED", null), "ck_payment_captures_reason");
        refuses(() -> insertCapture(UUID.randomUUID(), order, "PENDING_VERIFICATION", "SECOND_CAPTURE"),
                "ck_payment_captures_reason");
        refuses(() -> insertCapture(UUID.randomUUID(), order, "NOT_APPLIED", "NO_SUCH_REASON"),
                "ck_payment_captures_reason");
        refuses(() -> jdbc.update("INSERT INTO payment_captures (id, capture_id, restaurant_id, order_id,"
                + " payment_attempt_id, amount_minor, currency, status, source_event_id, occurred_at, received_at)"
                + " VALUES (?, ?, ?, ?, ?, 9000, 'TRY', 'PENDING_VERIFICATION', ?, now(), now())", UUID.randomUUID(),
                UUID.randomUUID(), DevFixtures.RESTAURANT_B, order, UUID.randomUUID(), UUID.randomUUID()),
                "fk_payment_captures_order");
        refuses(() -> jdbc.update("INSERT INTO payment_captures (id, capture_id, restaurant_id, order_id,"
                + " payment_attempt_id, amount_minor, currency, status, source_event_id, occurred_at, received_at)"
                + " VALUES (?, ?, ?, ?, ?, -1, 'TRY', 'PENDING_VERIFICATION', ?, now(), now())", UUID.randomUUID(),
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, order, UUID.randomUUID(), UUID.randomUUID()),
                "ck_payment_captures_amount");
    }

    @Test
    void aCaptureIsNeverRewrittenOrDeletedAndOnlyAWaitingOneMayBeResolved() throws Exception {
        UUID order = placeSaladOrder(A1);
        UUID waiting = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        insertCapture(waiting, order, "PENDING_VERIFICATION", null);
        insertCapture(refused, order, "NOT_APPLIED", "SECOND_CAPTURE");

        refuses(() -> jdbc.update("UPDATE payment_captures SET amount_minor = 1 WHERE capture_id = ?", waiting),
                "immutable");
        refuses(() -> jdbc.update("UPDATE payment_captures SET order_id = ? WHERE capture_id = ?", UUID.randomUUID(),
                waiting), "immutable");
        refuses(() -> jdbc.update("DELETE FROM payment_captures WHERE capture_id = ?", waiting), "never deleted");
        refuses(() -> jdbc.update("UPDATE payment_captures SET status = 'PENDING_VERIFICATION', not_applied_reason ="
                + " NULL, resolved_at = NULL WHERE capture_id = ?", refused), "final");
        // a waiting collection can be resolved once
        jdbc.update("UPDATE payment_captures SET status = 'NOT_APPLIED', not_applied_reason = 'ORDER_CANCELLED',"
                + " resolved_at = now() WHERE capture_id = ?", waiting);
        refuses(() -> jdbc.update("UPDATE payment_captures SET status = 'NOT_APPLIED', not_applied_reason ="
                + " 'SECOND_CAPTURE' WHERE capture_id = ?", waiting), "final");
    }

    @Test
    void reconciliationCasesAreAppendOnlyAndUniquePerOrderCaptureAndReason() throws Exception {
        UUID order = placeSaladOrder(A1);
        UUID capture = UUID.randomUUID();
        insertCapture(capture, order, "NOT_APPLIED", "SECOND_CAPTURE");
        String insert = "INSERT INTO payment_reconciliation_cases (id, restaurant_id, order_id, capture_id,"
                + " payment_attempt_id, reason, reported_amount_minor, reported_currency, source_event_id, occurred_at,"
                + " fulfillment_status, payment_status, created_at) VALUES (?, ?, ?, ?, ?, ?, 9000, 'TRY', ?, now(),"
                + " 'RECEIVED', 'PAID', now())";
        jdbc.update(insert, UUID.randomUUID(), DevFixtures.RESTAURANT_A, order, capture, UUID.randomUUID(),
                "SECOND_CAPTURE", UUID.randomUUID());
        refuses(() -> jdbc.update(insert, UUID.randomUUID(), DevFixtures.RESTAURANT_A, order, capture, UUID.randomUUID(),
                "SECOND_CAPTURE", UUID.randomUUID()), "uq_payment_reconciliation_case");
        refuses(() -> jdbc.update(insert, UUID.randomUUID(), DevFixtures.RESTAURANT_A, order, capture, UUID.randomUUID(),
                "SOMETHING_ELSE", UUID.randomUUID()), "ck_payment_reconciliation_reason");
        refuses(() -> jdbc.update(insert, UUID.randomUUID(), DevFixtures.RESTAURANT_A, order, UUID.randomUUID(),
                UUID.randomUUID(), "SECOND_CAPTURE", UUID.randomUUID()), "fk_payment_reconciliation_capture");
        refuses(() -> jdbc.update("UPDATE payment_reconciliation_cases SET reason = 'ORDER_CANCELLED'"), "append-only");
        refuses(() -> jdbc.update("DELETE FROM payment_reconciliation_cases"), "append-only");
    }

    @Test
    void twoTransactionsRacingForOneCaptureIdLetExactlyOneWin() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID capture = UUID.randomUUID();
        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            TransactionTemplate tx = new TransactionTemplate(transactions);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                UUID order = i % 2 == 0 ? first : second;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return tx.execute(status -> store.claim(new Capture(capture, DevFixtures.RESTAURANT_A, order,
                            UUID.randomUUID(), 9000, "TRY", PaymentCaptureStore.PENDING_VERIFICATION, null,
                            UUID.randomUUID(), Instant.now()), Instant.now()));
                }));
            }
            ready.await();
            go.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners, "only one of the racing transactions recorded the capture");
            assertEquals(1, count("payment_captures"));
        } finally {
            pool.shutdownNow();
        }
    }
}
