package com.restofy.order.api.controller;

import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V47: what the DATABASE itself guarantees about cancellations, whatever the application code does. */
class CancellationDatabaseTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    @Autowired
    private PlatformTransactionManager transactions;

    /** The statement (or its commit) must fail and the database's own message, anywhere in the chain, must say why. */
    private static void refuses(Runnable statement, String message) {
        RuntimeException error = assertThrows(RuntimeException.class, statement::run);
        assertTrue(chain(error).contains(message), chain(error));
    }

    private static String chain(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    /** A cancellation waiting for the kitchen: the order is in preparation. */
    private UUID requested(UUID orderId) throws Exception {
        advanceTo(orderId, "PREPARING");
        JsonNode created = body(cancelAll(guest(A1), orderId).andExpect(status().isCreated()));
        return UUID.fromString(created.path("cancellationId").asString());
    }

    private UUID applied(UUID orderId) throws Exception {
        JsonNode created = body(cancelAll(guest(A1), orderId).andExpect(status().isCreated()));
        return UUID.fromString(created.path("cancellationId").asString());
    }

    private void insertCancellation(UUID id, UUID orderId, String status, String extraColumns, String extraValues) {
        jdbc.update("INSERT INTO order_cancellations (id, restaurant_id, order_id, scope, status, requested_by_type,"
                + " requested_by_id, requested_at, kitchen_confirmation_required, fulfillment_status_at_request,"
                + " payment_status_at_request, currency, previous_due_minor, cancelled_gross_minor, cancelled_net_minor,"
                + " cancelled_vat_minor, new_due_minor, collected_minor, created_at, updated_at" + extraColumns
                + ") VALUES (?, ?, ?, 'FULL', ?, 'STAFF', 'x', now(), true, 'PREPARING', 'NOT_REQUESTED', 'TRY', 9000,"
                + " 9000, 7500, 1500, 0, 0, now(), now()" + extraValues + ")", id, DevFixtures.RESTAURANT_A, orderId,
                status);
    }

    // ---- the counter ------------------------------------------------------------------------------------------------

    @Test
    void theCancelledQuantityCannotBeChangedByHand() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        refuses(() -> jdbc.update("UPDATE order_items SET cancelled_quantity = 1 WHERE order_id = ?", orderId),
                "does not match");
        assertEquals(0, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
    }

    @Test
    void aCancellationCannotBeAppliedWithoutMovingTheCounterAndTheCounterCannotMoveWithoutACancellation()
            throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellation = requested(orderId);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        String apply = "UPDATE order_cancellations SET status = 'APPLIED', decision_source = 'KITCHEN',"
                + " decided_by_type = 'SERVICE', decided_by_id = 'KITCHEN', decided_at = now() WHERE id = ?";

        // decided as APPLIED but the counter was not moved: refused at commit
        refuses(() -> tx.executeWithoutResult(status -> jdbc.update(apply, cancellation)), "does not match");
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM order_cancellations WHERE id = ?",
                String.class, cancellation));

        // both together, in one transaction, in either order: accepted
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE order_items SET cancelled_quantity = 1 WHERE order_id = ?", orderId);
            jdbc.update(apply, cancellation);
        });
        assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM order_cancellations WHERE id = ?", String.class,
                cancellation));

        // a cancellation that was applied can not be taken back by hand
        refuses(() -> jdbc.update("UPDATE order_items SET cancelled_quantity = 0 WHERE order_id = ?", orderId),
                "does not match");
    }

    @Test
    void aRejectedRequestLeavesTheCounterAlone() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellation = requested(orderId);

        jdbc.update("UPDATE order_cancellations SET status = 'REJECTED', decision_source = 'KITCHEN',"
                + " decided_by_type = 'SERVICE', decided_by_id = 'KITCHEN', decided_at = now(),"
                + " rejection_code = 'ALREADY_COOKED' WHERE id = ?", cancellation);
        refuses(() -> jdbc.update("UPDATE order_items SET cancelled_quantity = 1 WHERE order_id = ?", orderId),
                "does not match");
    }

    // ---- the cancellation row ---------------------------------------------------------------------------------------

    @Test
    void aDecisionIsFinalAndWhoAskedNeverChanges() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellation = applied(orderId);

        for (String change : new String[]{"status = 'REJECTED'", "cancelled_gross_minor = 1", "new_due_minor = 5",
                "decision_source = 'KITCHEN'", "decided_at = now()", "collected_minor = 1", "refund_required_minor = 1"}) {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> jdbc.update("UPDATE order_cancellations SET " + change + " WHERE id = ?", cancellation), change);
            assertTrue(chain(error).contains("final"), change + ": " + chain(error));
        }
        for (String change : new String[]{"requested_by_id = 'someone else'", "requested_by_type = 'STAFF'",
                "requested_at = now()", "scope = 'PARTIAL'", "fulfillment_status_at_request = 'PREPARING'",
                "kitchen_confirmation_required = true", "currency = 'USD'"}) {
            refuses(() -> jdbc.update("UPDATE order_cancellations SET " + change + " WHERE id = ?", cancellation),
                    "immutable");
        }
        refuses(() -> jdbc.update("DELETE FROM order_cancellations WHERE id = ?", cancellation), "never deleted");
    }

    @Test
    void theRefundThatHappenedCanOnlyGrowAndNeverExceedsWhatIsRequired() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        pay(orderId, "PAID", 5).andExpect(status().isOk());
        JsonNode created = body(cancelAll(WAITER, orderId).andExpect(status().isCreated()));
        UUID cancellation = UUID.fromString(created.path("cancellationId").asString());

        assertEquals(1, jdbc.update("UPDATE order_cancellations SET refunded_minor = 5000 WHERE id = ?", cancellation));
        refuses(() -> jdbc.update("UPDATE order_cancellations SET refunded_minor = 1000 WHERE id = ?", cancellation),
                "cannot be taken back");
        refuses(() -> jdbc.update("UPDATE order_cancellations SET refunded_minor = 9001 WHERE id = ?", cancellation),
                "ck_order_cancellations_amounts");
        assertEquals(1, jdbc.update("UPDATE order_cancellations SET refunded_minor = 9000 WHERE id = ?", cancellation));
    }

    @Test
    void theDecisionColumnsAreConsistent() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        // a request carries no decision; a decision carries all of it
        refuses(() -> insertCancellation(UUID.randomUUID(), orderId, "REQUESTED", ", decided_at", ", now()"),
                "ck_order_cancellations_decision");
        refuses(() -> insertCancellation(UUID.randomUUID(), orderId, "APPLIED", "", ""),
                "ck_order_cancellations_decision");
        refuses(() -> insertCancellation(UUID.randomUUID(), orderId, "REJECTED",
                ", decided_at, decision_source, decided_by_type", ", now(), 'KITCHEN', 'SERVICE'"),
                "ck_order_cancellations_decision");
        refuses(() -> insertCancellation(UUID.randomUUID(), orderId, "BOGUS", "", ""), "violates check constraint");
        assertEquals(0, count("order_cancellations"));
    }

    @Test
    void theAmountsMustAddUp() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        refuses(() -> jdbc.update("INSERT INTO order_cancellations (id, restaurant_id, order_id, scope, status,"
                + " requested_by_type, requested_by_id, requested_at, kitchen_confirmation_required,"
                + " fulfillment_status_at_request, payment_status_at_request, currency, previous_due_minor,"
                + " cancelled_gross_minor, cancelled_net_minor, cancelled_vat_minor, new_due_minor, collected_minor,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'FULL', 'REQUESTED', 'STAFF', 'x', now(), true, 'PREPARING',"
                + " 'NOT_REQUESTED', 'TRY', 9000, 9000, 7500, 1000, 0, 0, now(), now())", UUID.randomUUID(),
                DevFixtures.RESTAURANT_A, orderId), "ck_order_cancellations_amounts");
        refuses(() -> jdbc.update("INSERT INTO order_cancellations (id, restaurant_id, order_id, scope, status,"
                + " requested_by_type, requested_by_id, requested_at, kitchen_confirmation_required,"
                + " fulfillment_status_at_request, payment_status_at_request, currency, previous_due_minor,"
                + " cancelled_gross_minor, cancelled_net_minor, cancelled_vat_minor, new_due_minor, collected_minor,"
                + " refund_required_minor, created_at, updated_at) VALUES (?, ?, ?, 'FULL', 'REQUESTED', 'STAFF', 'x',"
                + " now(), true, 'PREPARING', 'NOT_REQUESTED', 'TRY', 9000, 9000, 7500, 1500, 0, 0, 1, now(), now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId), "ck_order_cancellations_amounts");
    }

    @Test
    void onlyOneRequestCanWaitPerOrderAndACancellationBelongsToItsOwnRestaurantsOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        requested(orderId);

        refuses(() -> insertCancellation(UUID.randomUUID(), orderId, "REQUESTED", "", ""),
                "uq_order_cancellations_one_open_per_order");
        refuses(() -> jdbc.update("INSERT INTO order_cancellations (id, restaurant_id, order_id, scope, status,"
                + " requested_by_type, requested_by_id, requested_at, kitchen_confirmation_required,"
                + " fulfillment_status_at_request, payment_status_at_request, currency, previous_due_minor,"
                + " cancelled_gross_minor, cancelled_net_minor, cancelled_vat_minor, new_due_minor, collected_minor,"
                + " decided_at, decision_source, decided_by_type, created_at, updated_at) VALUES (?, ?, ?, 'FULL', 'APPLIED',"
                + " 'STAFF', 'x', now(), false, 'RECEIVED', 'NOT_REQUESTED', 'TRY', 9000, 9000, 7500, 1500, 0, 0,"
                + " now(), 'AUTO', 'SYSTEM', now(), now())", UUID.randomUUID(),
                DevFixtures.RESTAURANT_B, orderId), "fk_order_cancellations_order");
    }

    // ---- who acts: GUEST, STAFF, SERVICE (D-13) ----------------------------------------------------------------------

    private void insertRequest(UUID orderId, String type, String requestedById, String role, UUID reportedStaff) {
        jdbc.update("INSERT INTO order_cancellations (id, restaurant_id, order_id, scope, status, requested_by_type,"
                + " requested_by_id, requested_by_role, reported_staff_id, requested_at, kitchen_confirmation_required,"
                + " fulfillment_status_at_request, payment_status_at_request, currency, previous_due_minor,"
                + " cancelled_gross_minor, cancelled_net_minor, cancelled_vat_minor, new_due_minor, collected_minor,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'FULL', 'REQUESTED', ?, ?, ?, ?, now(), true, 'PREPARING',"
                + " 'NOT_REQUESTED', 'TRY', 9000, 9000, 7500, 1500, 0, 0, now(), now())", UUID.randomUUID(),
                DevFixtures.RESTAURANT_A, orderId, type, requestedById, role, reportedStaff);
    }

    @Test
    void aServiceRequesterIsExactlyTheKitchenService() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        insertRequest(orderId, "SERVICE", "KITCHEN", "KITCHEN", null);
        assertEquals(1, count("order_cancellations"));
    }

    @Test
    void noOtherServiceAndNoLooseServiceRecordIsAccepted() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        for (String[] bad : new String[][]{{"PAYMENT", "PAYMENT"}, {"KITCHEN", "WAITER"}, {"KITCHEN", null},
                {"KITCHEN_SERVICE", "KITCHEN_SERVICE"}, {"service:KITCHEN", "KITCHEN"}, {"kitchen", "kitchen"}}) {
            refuses(() -> insertRequest(orderId, "SERVICE", bad[0], bad[1], null),
                    "ck_order_cancellations_service_requester");
        }
        // the old kinds still exist and the set is closed
        refuses(() -> insertRequest(orderId, "SYSTEM", "x", null, null), "ck_order_cancellations_requester");
        refuses(() -> insertRequest(orderId, "ROBOT", "x", null, null), "ck_order_cancellations_requester");
        assertEquals(0, count("order_cancellations"));
        insertRequest(orderId, "STAFF", UUID.randomUUID().toString(), "WAITER", null);
        assertEquals(1, count("order_cancellations"));
    }

    @Test
    void theStaffMemberAServiceClaimsExistsOnlyNextToAServiceRequest() throws Exception {
        UUID claimed = UUID.randomUUID();
        UUID guestOrder = placeSaladOrder(A1);
        UUID staffOrder = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID serviceOrder = placeMixedOrder(A1);

        refuses(() -> insertRequest(guestOrder, "GUEST", "guest-session", null, claimed),
                "ck_order_cancellations_reported_staff");
        refuses(() -> insertRequest(staffOrder, "STAFF", UUID.randomUUID().toString(), "WAITER", claimed),
                "ck_order_cancellations_reported_staff");
        insertRequest(serviceOrder, "SERVICE", "KITCHEN", "KITCHEN", claimed);

        assertEquals(claimed, jdbc.queryForObject("SELECT reported_staff_id FROM order_cancellations", UUID.class));
        assertEquals("KITCHEN", jdbc.queryForObject("SELECT requested_by_id FROM order_cancellations", String.class),
                "the claim never becomes the author");
    }

    @Test
    void theClaimIsAsUnchangeableAsTheAuthorAndCannotBeAddedLater() throws Exception {
        UUID withClaim = placeSaladOrder(A1);
        UUID withoutClaim = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID claimed = UUID.randomUUID();
        insertRequest(withClaim, "SERVICE", "KITCHEN", "KITCHEN", claimed);
        insertRequest(withoutClaim, "SERVICE", "KITCHEN", "KITCHEN", null);

        refuses(() -> jdbc.update("UPDATE order_cancellations SET reported_staff_id = ? WHERE order_id = ?",
                UUID.randomUUID(), withClaim), "immutable");
        refuses(() -> jdbc.update("UPDATE order_cancellations SET reported_staff_id = NULL WHERE order_id = ?", withClaim),
                "immutable");
        refuses(() -> jdbc.update("UPDATE order_cancellations SET reported_staff_id = ? WHERE order_id = ?", claimed,
                withoutClaim), "immutable");
        // nor can the service be turned into a staff member or the other way round
        refuses(() -> jdbc.update("UPDATE order_cancellations SET requested_by_type = 'STAFF' WHERE order_id = ?",
                withClaim), "immutable");
        refuses(() -> jdbc.update("UPDATE order_cancellations SET requested_by_id = ? WHERE order_id = ?",
                claimed.toString(), withClaim), "immutable");
        refuses(() -> jdbc.update("UPDATE order_cancellations SET requested_by_role = 'ADMIN' WHERE order_id = ?",
                withClaim), "immutable");
        assertEquals(claimed, jdbc.queryForObject("SELECT reported_staff_id FROM order_cancellations WHERE order_id = ?",
                UUID.class, withClaim));
    }

    private UUID appliedAs(String authorization, UUID orderId) throws Exception {
        return UUID.fromString(body(cancelAll(authorization, orderId).andExpect(status().isCreated()))
                .path("cancellationId").asString());
    }

    private void insertReasonAs(UUID cancellation, String source, String code, String addedById, String role) {
        jdbc.update("INSERT INTO order_cancellation_reasons (id, restaurant_id, cancellation_id, source, reason_code,"
                        + " reason_text, added_by_type, added_by_id, added_by_role, added_at)"
                        + " VALUES (?, ?, ?, ?, ?, null, ?, ?, ?, now())", UUID.randomUUID(), DevFixtures.RESTAURANT_A,
                cancellation, source, code, source, addedById, role);
    }

    @Test
    void aServiceReasonIsTheKitchenServicesOwnAndNeverMixesWithAStaffMembers() throws Exception {
        UUID byService = applied(placeSaladOrder(A1));
        UUID byStaff = appliedAs(guest(DevFixtures.TOKEN_A2), placeSaladOrder(DevFixtures.TOKEN_A2));

        refuses(() -> insertReasonAs(byService, "SERVICE", "OTHER", "someone", null), "ck_order_cancellation_reasons_service");
        refuses(() -> insertReasonAs(byService, "SERVICE", "OTHER", "KITCHEN", "WAITER"),
                "ck_order_cancellation_reasons_service");
        refuses(() -> insertReasonAs(byService, "SERVICE", "OTHER", "KITCHEN", null),
                "ck_order_cancellation_reasons_service");
        refuses(() -> insertReasonAs(byService, "ROBOT", "OTHER", "KITCHEN", "KITCHEN"),
                "ck_order_cancellation_reasons_source");
        insertReasonAs(byService, "SERVICE", "ITEM_UNAVAILABLE", "KITCHEN", "KITCHEN");
        refuses(() -> insertReasonAs(byService, "STAFF", "OTHER", UUID.randomUUID().toString(), "WAITER"),
                "never mixed");

        insertReasonAs(byStaff, "STAFF", "OTHER", UUID.randomUUID().toString(), "WAITER");
        refuses(() -> insertReasonAs(byStaff, "SERVICE", "KITCHEN_ERROR", "KITCHEN", "KITCHEN"), "never mixed");
        assertEquals(2, count("order_cancellation_reasons"));
    }

    @Test
    void aServiceCannotAddToTheCustomersReasonAndTheCustomerCannotFollowAService() throws Exception {
        UUID byGuest = applied(placeSaladOrder(A1));
        insertReason(byGuest, "GUEST", "CHANGED_MIND", null);
        refuses(() -> insertReasonAs(byGuest, "SERVICE", "OTHER", "KITCHEN", "KITCHEN"),
                "the customer already gave a reason");

        UUID byService = appliedAs(guest(DevFixtures.TOKEN_A2), placeSaladOrder(DevFixtures.TOKEN_A2));
        insertReasonAs(byService, "SERVICE", "OTHER", "KITCHEN", "KITCHEN");
        refuses(() -> insertReason(byService, "GUEST", "CHANGED_MIND", null), "already completed");
    }

    // ---- the lines --------------------------------------------------------------------------------------------------

    @Test
    void cancellationLinesAreAppendOnlyAndMatchTheOrderSnapshot() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID salad = lineOf(orderId, DevFixtures.SALAD);
        JsonNode created = body(cancel(guest(A1), orderId, newKey(), cancelItems(salad, 1))
                .andExpect(status().isCreated()));
        UUID cancellation = UUID.fromString(created.path("cancellationId").asString());
        UUID burger = lineOf(orderId, DevFixtures.BURGER);

        refuses(() -> jdbc.update("UPDATE order_cancellation_items SET quantity = 2 WHERE cancellation_id = ?",
                cancellation), "append-only");
        refuses(() -> jdbc.update("DELETE FROM order_cancellation_items WHERE cancellation_id = ?", cancellation),
                "append-only");
        // a line of this order, but with another price than the snapshot
        refuses(() -> insertItem(cancellation, burger, 1, 100, 1000, 100, 0, DevFixtures.BURGER), "does not match");
        // more units than were ordered
        refuses(() -> insertItem(cancellation, burger, 2, 25_000, 1000, 50_000, 45_455, DevFixtures.BURGER),
                "does not match");
        // a line of another order
        UUID other = placeSaladOrder(DevFixtures.TOKEN_A2);
        refuses(() -> insertItem(cancellation, lineOf(other, DevFixtures.SALAD), 1, 9000, 2000, 9000, 7500,
                DevFixtures.SALAD), "does not belong");
        // the same line twice in one cancellation
        refuses(() -> insertItem(cancellation, salad, 1, 9000, 2000, 9000, 7500, DevFixtures.SALAD),
                "uq_order_cancellation_items_line");
        assertEquals(1, count("order_cancellation_items"));
    }

    private void insertItem(UUID cancellation, UUID line, int quantity, long unit, int bps, long gross, long net,
                            UUID product) {
        jdbc.update("INSERT INTO order_cancellation_items (id, restaurant_id, cancellation_id, order_item_id, product_id,"
                        + " product_name_snapshot, quantity, unit_gross_minor, tax_rate_bps, gross_minor, net_minor,"
                        + " vat_minor) VALUES (?, ?, ?, ?, ?, 'x', ?, ?, ?, ?, ?, ?)", UUID.randomUUID(),
                DevFixtures.RESTAURANT_A, cancellation, line, product, quantity, unit, bps, gross, net, gross - net);
    }

    // ---- the reasons ------------------------------------------------------------------------------------------------

    private void insertReason(UUID cancellation, String source, String code, String text) {
        jdbc.update("INSERT INTO order_cancellation_reasons (id, restaurant_id, cancellation_id, source, reason_code,"
                        + " reason_text, added_by_type, added_by_id, added_by_role, added_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'someone', null, now())", UUID.randomUUID(),
                DevFixtures.RESTAURANT_A, cancellation, source, code, text, source);
    }

    @Test
    void reasonsAreAppendOnlyAndKeepTheirShape() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellation = applied(orderId);
        insertReason(cancellation, "GUEST", "OTHER", null);

        refuses(() -> jdbc.update("UPDATE order_cancellation_reasons SET reason_text = 'edited'"), "append-only");
        refuses(() -> jdbc.update("DELETE FROM order_cancellation_reasons"), "append-only");
        refuses(() -> insertReason(cancellation, "GUEST", "OTHER", null), "uq_order_cancellation_reasons_code");
        refuses(() -> insertReason(cancellation, "GUEST", "NO_SUCH_CODE", null), "ck_order_cancellation_reasons_code");
        refuses(() -> insertReason(cancellation, "GUEST", null, null), "ck_order_cancellation_reasons_content");
        refuses(() -> insertReason(cancellation, "GUEST", null, "   "), "ck_order_cancellation_reasons_text");
        refuses(() -> insertReason(cancellation, "SYSTEM", "OTHER", null), "ck_order_cancellation_reasons_source");
        insertReason(cancellation, "GUEST", null, "one free text");
        refuses(() -> insertReason(cancellation, "GUEST", null, "another free text"),
                "uq_order_cancellation_reasons_text");
        assertEquals(2, count("order_cancellation_reasons"));
    }

    @Test
    void aWaitersReasonCanNeverFollowOrPrecedeTheCustomersOwn() throws Exception {
        UUID withGuestReason = applied(placeSaladOrder(A1));
        insertReason(withGuestReason, "GUEST", "CHANGED_MIND", null);
        refuses(() -> insertReason(withGuestReason, "STAFF", "OTHER", null), "the customer already gave a reason");

        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        JsonNode created = body(cancelAll(guest(DevFixtures.TOKEN_A2), second).andExpect(status().isCreated()));
        UUID withStaffReason = UUID.fromString(created.path("cancellationId").asString());
        insertReason(withStaffReason, "STAFF", "OTHER", null);
        refuses(() -> insertReason(withStaffReason, "GUEST", "CHANGED_MIND", null), "already completed");
    }

    @Test
    void theCodeCatalogInTheCodeAndInTheDatabaseAreTheSame() {
        String definition = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'ck_order_cancellation_reasons_code'", String.class);
        Set<String> inDatabase = new HashSet<>();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (matcher.find()) {
            inDatabase.add(matcher.group(1));
        }
        Set<String> inCode = new HashSet<>(Arrays.stream(CancellationReasonCode.values()).map(Enum::name).toList());
        assertEquals(inCode, inDatabase);
        // and every code the database accepts is accepted by the API
        assertEquals(List.of(), inDatabase.stream().filter(code -> CancellationReasonCode.parse(code).isEmpty()).toList());
    }
}
