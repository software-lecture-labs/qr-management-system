package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V48: what the DATABASE itself guarantees about table-session closures, whatever the application code does. */
class TableSessionClosureDatabaseTests extends AbstractCartApiTest {

    private static final String FINGERPRINT = "a".repeat(64);

    private static void refuses(Runnable statement, String message) {
        RuntimeException error = assertThrows(RuntimeException.class, statement::run);
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        assertTrue(all.toString().contains(message), all.toString());
    }

    private void insert(UUID operation, UUID session, String status, String completedAt, String releasedAt) {
        insertWith(operation, session, "SETTLED", "SETTLED_CLOSE", "", status, completedAt, releasedAt, 1);
    }

    private void insertWith(UUID operation, UUID session, String purpose, String effect, String idleGuests,
                            String status, String completedAt, String releasedAt, int orderCount) {
        jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id, purpose, effect,"
                + " command_digest, idle_guest_ids, status, order_count, scope_fingerprint, prepared_at, completed_at,"
                + " released_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), " + completedAt + ", " + releasedAt + ")",
                operation, DevFixtures.RESTAURANT_A, session, purpose, effect, FINGERPRINT, idleGuests, status,
                orderCount, FINGERPRINT);
    }

    private void prepared(UUID operation, UUID session) {
        insert(operation, session, "PREPARED", "NULL", "NULL");
    }

    private String statusOf(UUID operation) {
        return jdbc.queryForObject("SELECT status FROM table_session_closures WHERE operation_id = ?", String.class,
                operation);
    }

    @Test
    void aTableSessionHasAtMostOneLiveClosure() {
        UUID session = DevFixtures.TABLE_SESSION_A1;
        prepared(UUID.randomUUID(), session);

        refuses(() -> prepared(UUID.randomUUID(), session), "uq_table_session_closures_live");
        refuses(() -> insert(UUID.randomUUID(), session, "COMPLETED", "now()", "NULL"), "uq_table_session_closures_live");
        // another session is independent
        prepared(UUID.randomUUID(), DevFixtures.TABLE_SESSION_B1);
        assertEquals(2, count("table_session_closures"));
    }

    @Test
    void aReleasedClosureDoesNotBlockANewOne() {
        UUID session = DevFixtures.TABLE_SESSION_A1;
        UUID first = UUID.randomUUID();
        prepared(first, session);
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = now() WHERE operation_id = ?",
                first);

        prepared(UUID.randomUUID(), session);

        assertEquals(2, count("table_session_closures"));
    }

    @Test
    void anOperationIdExistsOnce() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> prepared(operation, DevFixtures.TABLE_SESSION_B1), "pk_table_session_closures");
    }

    @Test
    void theStatusAndItsTimestampsMustAgree() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> insert(UUID.randomUUID(), session, "PREPARED", "now()", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "COMPLETED", "NULL", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "RELEASED", "NULL", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "RELEASED", "now()", "now()"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "LOST", "NULL", "NULL"), "ck_table_session_closures_status");
        assertEquals(0, count("table_session_closures"));
    }

    @Test
    void theFingerprintAndTheOrderCountMustBeValid() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id,"
                + " purpose, effect, command_digest, status, order_count, scope_fingerprint, prepared_at)"
                + " VALUES (?, ?, ?, 'SETTLED', 'SETTLED_CLOSE', ?, 'PREPARED', 1, 'not-hex', now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, session, FINGERPRINT),
                "ck_table_session_closures_fingerprint");
        refuses(() -> jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id,"
                + " purpose, effect, command_digest, status, order_count, scope_fingerprint, prepared_at)"
                + " VALUES (?, ?, ?, 'SETTLED', 'SETTLED_CLOSE', ?, 'PREPARED', 0, ?, now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, session, FINGERPRINT, FINGERPRINT),
                "ck_table_session_closures_count");
        refuses(() -> jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id,"
                + " purpose, effect, command_digest, status, order_count, scope_fingerprint, prepared_at)"
                + " VALUES (?, ?, ?, 'SETTLED', 'SETTLED_CLOSE', 'not-hex', 'PREPARED', 1, ?, now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, session, FINGERPRINT),
                "ck_table_session_closures_digest");
    }

    // ---- V48: purpose, effect, idle guests and the receipt ---------------------------------------------------------------

    private static final UUID GUEST = UUID.fromString("00000000-0000-4000-8000-00000000ee01");

    @Test
    void thePurposeAndTheEffectMustAgreeAndIdleGuestsBelongOnlyToAGuestExpiry() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> insertWith(UUID.randomUUID(), session, "SETTLED", "IDLE_MAIN", "", "PREPARED", "NULL", "NULL", 1),
                "ck_table_session_closures_purpose");
        refuses(() -> insertWith(UUID.randomUUID(), session, "IDLE", "SETTLED_CLOSE", "", "PREPARED", "NULL", "NULL", 0),
                "ck_table_session_closures_purpose");
        refuses(() -> insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_GUESTS", "", "PREPARED", "NULL", "NULL", 0),
                "ck_table_session_closures_purpose");
        refuses(() -> insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_MAIN", GUEST.toString(), "PREPARED", "NULL",
                "NULL", 0), "ck_table_session_closures_purpose");
        insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_GUESTS", GUEST.toString(), "PREPARED", "NULL", "NULL", 0);
        assertEquals(1, count("table_session_closures"));
    }

    @Test
    void aSettledCloseNeedsOrdersButAnIdleExpiryMayHaveNone() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> insertWith(UUID.randomUUID(), session, "SETTLED", "SETTLED_CLOSE", "", "PREPARED", "NULL", "NULL",
                0), "ck_table_session_closures_count");
        insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_MAIN", "", "PREPARED", "NULL", "NULL", 0);
        assertEquals(1, count("table_session_closures"));
    }

    @Test
    void aGuestExpiryThatCompletedDoesNotEndTheSessionButAnythingThatEndsItDoes() {
        UUID session = DevFixtures.TABLE_SESSION_A1;
        String guests = GUEST.toString();

        // two guest expiries can both be COMPLETED, and a new operation may be PREPARED next to them
        insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_GUESTS", guests, "COMPLETED", "now()", "NULL", 0);
        insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_GUESTS", guests, "COMPLETED", "now()", "NULL", 0);
        UUID next = UUID.randomUUID();
        insertWith(next, session, "SETTLED", "SETTLED_CLOSE", "", "PREPARED", "NULL", "NULL", 1);
        // but not a second live one, and nothing next to a COMPLETED close
        refuses(() -> insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_MAIN", "", "PREPARED", "NULL", "NULL", 0),
                "uq_table_session_closures_live");
        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = now() WHERE operation_id = ?",
                next);
        refuses(() -> insertWith(UUID.randomUUID(), session, "IDLE", "IDLE_MAIN", "", "PREPARED", "NULL", "NULL", 0),
                "uq_table_session_closures_live");
        assertEquals(3, count("table_session_closures"), "two guest expiries and the close; the refused ones left nothing");
    }

    @Test
    void aReceiptIsAllOrNothingAndOnlyACompletedOperationHasOne() {
        UUID prepared = UUID.randomUUID();
        prepared(prepared, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("UPDATE table_session_closures SET receipt_session_version = 1 WHERE operation_id = ?",
                prepared), "ck_table_session_closures_receipt");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET receipt_session_version = 1, receipt_table_version = 2,"
                + " receipt_completed_at = now(), receipt_expired_guests = 0 WHERE operation_id = ?", prepared),
                "ck_table_session_closures_receipt");

        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = now(),"
                + " receipt_session_version = 1, receipt_table_version = 2, receipt_completed_at = now(),"
                + " receipt_expired_guests = 0 WHERE operation_id = ?", prepared);
        assertEquals("COMPLETED", statusOf(prepared));
        refuses(() -> jdbc.update("UPDATE table_session_closures SET receipt_expired_guests = 9 WHERE operation_id = ?",
                prepared), "outcome is final");
    }

    @Test
    void thePurposeTheEffectAndTheDigestOfAnOperationNeverChange() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("UPDATE table_session_closures SET command_digest = ? WHERE operation_id = ?",
                "b".repeat(64), operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET effect = 'IDLE_MAIN', purpose = 'IDLE'"
                + " WHERE operation_id = ?", operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET idle_guest_ids = ? WHERE operation_id = ?",
                GUEST.toString(), operation), "is immutable");
    }

    @Test
    void aClosureIsNeverDeleted() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("DELETE FROM table_session_closures WHERE operation_id = ?", operation),
                "never deleted");
        assertEquals(1, count("table_session_closures"));
    }

    @Test
    void theFactsOfAClosureNeverChange() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("UPDATE table_session_closures SET scope_fingerprint = ? WHERE operation_id = ?",
                "b".repeat(64), operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET table_session_id = ? WHERE operation_id = ?",
                UUID.randomUUID(), operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET order_count = 5 WHERE operation_id = ?", operation),
                "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET prepared_at = now() - interval '1 day'"
                + " WHERE operation_id = ?", operation), "is immutable");
    }

    @Test
    void aPreparedClosureMovesOnceAndThenItIsFinal() {
        UUID completed = UUID.randomUUID();
        prepared(completed, DevFixtures.TABLE_SESSION_A1);
        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = now() WHERE operation_id = ?",
                completed);
        assertEquals("COMPLETED", statusOf(completed));

        refuses(() -> jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', completed_at = NULL,"
                + " released_at = now() WHERE operation_id = ?", completed), "outcome is final");
        assertEquals("COMPLETED", statusOf(completed));

        UUID released = UUID.randomUUID();
        prepared(released, DevFixtures.TABLE_SESSION_B1);
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = now() WHERE operation_id = ?",
                released);
        refuses(() -> jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', released_at = NULL,"
                + " completed_at = now() WHERE operation_id = ?", released), "outcome is final");
        assertEquals("RELEASED", statusOf(released));
    }

    // ---- V49: scope drift records ---------------------------------------------------------------------------------------

    private static final String OTHER_FINGERPRINT = "b".repeat(64);

    private void drift(UUID operation, String atPrepare, String atConfirm, String outcome) {
        jdbc.update("INSERT INTO table_session_closure_drifts (id, operation_id, restaurant_id, table_session_id,"
                + " fingerprint_at_prepare, fingerprint_at_confirm, outcome_at_confirm, blockers, detected_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PAYMENT_NOT_SETTLED', now())", UUID.randomUUID(), operation,
                DevFixtures.RESTAURANT_A, DevFixtures.TABLE_SESSION_A1, atPrepare, atConfirm, outcome);
    }

    @Test
    void aDriftBelongsToAnExistingClosureAndExistsOncePerOperation() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        drift(operation, FINGERPRINT, OTHER_FINGERPRINT, "NOT_ELIGIBLE");

        refuses(() -> drift(operation, FINGERPRINT, OTHER_FINGERPRINT, "UNKNOWN"),
                "uq_table_session_closure_drifts_operation");
        refuses(() -> drift(UUID.randomUUID(), FINGERPRINT, OTHER_FINGERPRINT, "UNKNOWN"),
                "fk_table_session_closure_drifts_closure");
        assertEquals(1, count("table_session_closure_drifts"));
    }

    @Test
    void aDriftNeedsTwoDifferentValidFingerprintsAndAKnownOutcome() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> drift(operation, FINGERPRINT, FINGERPRINT, "NOT_ELIGIBLE"),
                "ck_table_session_closure_drifts_fingerprints");
        refuses(() -> drift(operation, "not-hex", OTHER_FINGERPRINT, "NOT_ELIGIBLE"),
                "ck_table_session_closure_drifts_fingerprints");
        refuses(() -> drift(operation, FINGERPRINT, OTHER_FINGERPRINT, "MAYBE"),
                "ck_table_session_closure_drifts_outcome");
        assertEquals(0, count("table_session_closure_drifts"));
    }

    @Test
    void aDriftIsNeverChangedOrDeleted() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);
        drift(operation, FINGERPRINT, OTHER_FINGERPRINT, "NOT_ELIGIBLE");

        refuses(() -> jdbc.update("UPDATE table_session_closure_drifts SET blockers = '' WHERE operation_id = ?",
                operation), "append-only");
        refuses(() -> jdbc.update("DELETE FROM table_session_closure_drifts WHERE operation_id = ?", operation),
                "append-only");
        assertEquals(1, count("table_session_closure_drifts"));
    }
}
