package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.config.RetentionJob;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency retention, cart housekeeping, and the guarantee that neither can cause a second order. */
class DataRetentionApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Autowired
    private DataRetentionService retention;
    @Autowired
    private IdempotencyStore store;
    @Autowired
    private RetentionJob job;

    private void insertRecord(String key, String status, Instant expiresAt) {
        jdbc.update("INSERT INTO idempotency_records (id, restaurant_id, actor_scope, operation, idempotency_key,"
                        + " request_digest, resource_id, response_status, status, created_at, expires_at)"
                        + " VALUES (?, ?, 'guest:test', 'CREATE_ORDER', ?, 'digest', ?, 201, ?, now(), ?)",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, key, UUID.randomUUID(), status, Timestamp.from(expiresAt));
    }

    private int records() {
        return count("idempotency_records");
    }

    // ---- idempotency record retention ---------------------------------------------------------------------------

    @Test
    void purgeRemovesOnlyCompletedRecordsWhoseRetentionHasPassed() {
        Instant now = Instant.now();
        insertRecord("expired-completed-1", "COMPLETED", now.minus(1, ChronoUnit.HOURS));
        insertRecord("expired-completed-2", "COMPLETED", now.minus(30, ChronoUnit.DAYS));
        insertRecord("still-valid-completed", "COMPLETED", now.plus(1, ChronoUnit.HOURS));
        insertRecord("expired-but-in-progress", "IN_PROGRESS", now.minus(5, ChronoUnit.DAYS));

        assertEquals(2, retention.purgeExpiredIdempotencyRecords());

        List<String> left = jdbc.queryForList("SELECT idempotency_key FROM idempotency_records ORDER BY idempotency_key",
                String.class);
        assertEquals(List.of("expired-but-in-progress", "still-valid-completed"), left,
                "an unexpired record and an expired-but-still-in-progress record must survive");
    }

    @Test
    void purgeIsRepeatableAndSafeWhenThereIsNothingToDo() {
        insertRecord("expired-1", "COMPLETED", Instant.now().minus(1, ChronoUnit.HOURS));
        assertEquals(1, retention.purgeExpiredIdempotencyRecords());
        assertEquals(0, retention.purgeExpiredIdempotencyRecords());
        assertEquals(0, retention.purgeExpiredIdempotencyRecords());
        assertEquals(0, records());
    }

    @Test
    void purgeWorksInBoundedBatchesAndTheServiceDrainsThem() {
        for (int i = 0; i < 7; i++) {
            insertRecord("expired-batch-" + i, "COMPLETED", Instant.now().minus(1, ChronoUnit.HOURS));
        }
        assertEquals(3, store.purgeExpired(Instant.now(), 3), "one batch deletes at most the batch size");
        assertEquals(4, records());
        assertEquals(4, retention.purgeExpiredIdempotencyRecords(), "the service keeps going until nothing is left");
        assertEquals(0, records());
    }

    @Test
    void theScheduledJobRunsTheSamePurgeAndSwallowsNothingItShouldKeep() {
        insertRecord("expired-job", "COMPLETED", Instant.now().minus(1, ChronoUnit.HOURS));
        insertRecord("valid-job", "COMPLETED", Instant.now().plus(1, ChronoUnit.HOURS));
        job.run();
        assertEquals(List.of("valid-job"), jdbc.queryForList("SELECT idempotency_key FROM idempotency_records", String.class));
    }

    @Test
    void recordsWrittenByTheApiGetTheDocumentedRetention() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        long hours = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) / 3600 FROM idempotency_records"
                + " WHERE operation = 'CREATE_ORDER'", Long.class);
        assertEquals(72, hours, "checkout records are kept 72 hours");
    }

    // ---- the critical rule: an expired/purged record never allows a second order ----------------------------------

    @Test
    void aPurgedCheckoutRecordNeverLetsTheSameCartBeOrderedAgain() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        placeOrder(A1, key, body).andExpect(status().isCreated());
        assertEquals(1, count("orders"));

        // retention passes and the record is purged
        jdbc.update("UPDATE idempotency_records SET expires_at = now() - interval '1 hour'");
        assertEquals(1, retention.purgeExpiredIdempotencyRecords());
        assertEquals(0, records());

        // the very same request (same key, same body) must not create another order
        placeOrder(A1, key, body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        // neither does a new key
        placeOrder(A1, newKey(), body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        assertEquals(1, count("orders"));
        assertEquals(1, count("outbox_events"));
    }

    @Test
    void theDatabaseItselfRefusesASecondOrderForAConsumedCart() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        jdbc.update("DELETE FROM idempotency_records");

        UUID cartId = jdbc.queryForObject("SELECT cart_id FROM orders", UUID.class);
        // even a hand-written insert (a bug, a replayed migration, a second instance) hits the unique constraint
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id, guest_session_id,"
                        + " fulfillment_status, payment_status, currency, initial_net_minor, initial_vat_minor,"
                        + " initial_gross_minor, current_due_minor, placed_at, version, created_at, updated_at)"
                        + " VALUES (?, ?, 'ORD-999999', ?, ?, ?, ?, 'RECEIVED', 'NOT_REQUESTED', 'TRY', 1, 0, 1, 1,"
                        + " now(), 0, now(), now())", UUID.randomUUID(), DevFixtures.RESTAURANT_A, cartId,
                DevFixtures.TABLE_A1, DevFixtures.TABLE_SESSION_A1, DevFixtures.GUEST_A1));
        assertEquals(1, count("orders"));
    }

    @Test
    void anOrderedCartCanNeverBecomeActiveAgain() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());

        DataAccessException error = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE carts SET status = 'ACTIVE'"));
        assertEquals(true, error.getMessage().contains("can no longer change"), error.getMessage());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE carts SET status = 'EXPIRED'"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts", String.class));
        // and the app cannot see it as an active cart either
        getCart(A1).andExpect(jsonPath("$.cartId").value((Object) null));
    }

    @Test
    void anExpiredCartCannotBeRevivedEither() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        jdbc.update("UPDATE carts SET status = 'EXPIRED'");
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE carts SET status = 'ACTIVE'"));
    }

    // ---- expired cart housekeeping ------------------------------------------------------------------------------------

    private UUID insertCart(String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO carts (id, restaurant_id, table_id, table_session_id, guest_session_id, status, version,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, now(), now())",
                id, DevFixtures.RESTAURANT_A, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        if (!"ACTIVE".equals(status)) {
            jdbc.update("UPDATE carts SET status = ? WHERE id = ?", status, id);
        }
        jdbc.update("UPDATE carts SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), id);
        return id;
    }

    @Test
    void onlyLongDeadExpiredCartsAreRemovedAndTheirLinesGoWithThem() throws Exception {
        Instant old = Instant.now().minus(60, ChronoUnit.DAYS);
        UUID oldExpired = insertCart("EXPIRED", old);
        jdbc.update("INSERT INTO cart_items (id, restaurant_id, cart_id, product_id, quantity, selection_key,"
                        + " product_name_snapshot, currency, unit_gross_minor_snapshot, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 1, 'k', 'p', 'TRY', 1, now(), now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, oldExpired, UUID.randomUUID());
        UUID recentExpired = insertCart("EXPIRED", Instant.now().minus(2, ChronoUnit.DAYS));
        UUID oldActive = insertCart("ACTIVE", old);
        UUID oldCheckedOut = insertCart("CHECKED_OUT", old);

        assertEquals(1, retention.purgeExpiredCarts());

        List<UUID> remaining = jdbc.queryForList("SELECT id FROM carts", UUID.class);
        assertEquals(3, remaining.size());
        assertEquals(true, remaining.containsAll(List.of(recentExpired, oldActive, oldCheckedOut)));
        assertEquals(0, count("cart_items"), "the deleted cart's lines are removed with it");
        assertEquals(0, retention.purgeExpiredCarts(), "repeatable");
    }

    @Test
    void cartHousekeepingNeverTouchesOrdersOrTheirHistory() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        jdbc.update("UPDATE carts SET updated_at = now() - interval '400 days'");
        jdbc.update("UPDATE idempotency_records SET expires_at = now() - interval '1 hour'");

        retention.runAll();

        assertEquals(1, count("carts"), "the ordered cart is kept");
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals(4, count("outbox_deliveries"));
        assertEquals(0, records(), "only the expired idempotency record went");
    }

    /**
     * Nothing in the Order Service marks a cart EXPIRED yet: that needs the QR Service to tell us a session ended
     * (integration pending). This pins what happens to an EXPIRED cart once something does: it is ignored by the
     * guest API and can never be revived, a new cart is started instead, and it is cleaned up later.
     */
    @Test
    void anExpiredCartIsIgnoredByTheGuestApiAndReplacedByANewOne() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());
        UUID expired = jdbc.queryForObject("SELECT id FROM carts", UUID.class);
        jdbc.update("UPDATE carts SET status = 'EXPIRED' WHERE id = ?", expired);

        getCart(A1).andExpect(jsonPath("$.cartId").value((Object) null)).andExpect(jsonPath("$.items.length()").value(0));
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());

        assertEquals(2, count("carts"));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM carts WHERE id = ?", String.class, expired));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM carts WHERE status = 'ACTIVE'", Integer.class));
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        assertEquals(1, count("orders"));
    }
}
