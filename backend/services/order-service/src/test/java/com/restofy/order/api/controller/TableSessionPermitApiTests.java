package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001: what the Order Service answers is the QR Service's "authority": status, effect, source revision,
 * verification time, validity, completeness and the checkout fence. These tests pin that shape, how a permit is renewed
 * or withdrawn before anything is applied, the command digest, the receipt and the abort.
 */
class TableSessionPermitApiTests extends AbstractClosureApiTest {

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(node.path(field).asString());
    }

    // ---- the shape of a permit ----------------------------------------------------------------------------------------

    @Test
    void anAuthorisedPermitCarriesEverythingTheQrServiceChecksBeforeApplying() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);

        JsonNode permit = body(prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("SETTLED"))
                .andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"))
                .andExpect(jsonPath("$.effect").value("SETTLED_CLOSE"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.checkoutFenced").value(true))
                .andExpect(jsonPath("$.idleGuestIds").isEmpty()));

        assertTrue(permit.path("sourceRevision").asLong() > 0);
        assertEquals(Duration.ofMinutes(1), Duration.between(instant(permit, "verifiedAt"),
                instant(permit, "validUntil")), "a permit is usable for a short, fixed time");
    }

    @Test
    void aBlockedOrUnknownAnswerIsStillACompleteAndCurrentAnswer() throws Exception {
        // the QR Service only acts (and aborts) on an answer that is current; so even a refusal must be one
        JsonNode blocked = body(prepare(UUID.randomUUID()).andExpect(jsonPath("$.sourceStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.effect").value("NONE")).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.checkoutFenced").value(false)));
        assertTrue(blocked.path("sourceRevision").asLong() >= 1);
        assertTrue(instant(blocked, "validUntil").isAfter(instant(blocked, "verifiedAt")));

        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        JsonNode unknown = body(prepare(UUID.randomUUID()).andExpect(jsonPath("$.sourceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.effect").value("NONE")).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.checkoutFenced").value(false)));
        assertTrue(instant(unknown, "validUntil").isAfter(instant(unknown, "verifiedAt")));
    }

    @Test
    void anOperationTheOrderServiceNeverSawIsACompleteAnswerToo() throws Exception {
        JsonNode never = body(lookup(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED")).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.effect").value("NONE")).andExpect(jsonPath("$.checkoutFenced").value(false)));

        assertTrue(never.path("sourceRevision").asLong() >= 1);
        assertTrue(instant(never, "validUntil").isAfter(instant(never, "verifiedAt")));
        assertEquals(0, closures());
    }

    @Test
    void theSourceRevisionOnlyGoesUpAsTheOrdersChange() throws Exception {
        long empty = body(orderState()).path("revision").asLong();
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        long placed = body(orderState()).path("revision").asLong();
        advanceTo(orderId, "COMPLETED");
        long served = body(orderState()).path("revision").asLong();
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());
        long paid = body(orderState()).path("revision").asLong();

        assertTrue(empty >= 1);
        assertTrue(placed > empty, "an order was placed");
        assertTrue(served > placed, "the order moved along");
        assertTrue(paid > served, "the payment was recorded");
        // the permit's revision is the same number
        assertEquals(paid, body(prepare(UUID.randomUUID())).path("sourceRevision").asLong());
    }

    // ---- renewing and withdrawing a permit before anything is applied --------------------------------------------------

    @Test
    void askingAgainRenewsThePermit() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        JsonNode first = body(prepare(operation));

        JsonNode renewed = body(lookup(operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("AUTHORISED")).andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.checkoutFenced").value(true)));

        assertFalse(instant(renewed, "verifiedAt").isBefore(instant(first, "verifiedAt")));
        assertEquals(Duration.ofMinutes(1), Duration.between(instant(renewed, "verifiedAt"),
                instant(renewed, "validUntil")));
        assertEquals(1, closures(), "renewing writes nothing");
    }

    @Test
    void askingAgainWithdrawsThePermitWhenTheOrdersChangedBeforeAnythingWasApplied() throws Exception {
        UUID orderId = finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"));
        // something about the money changed before the QR Service closed anything
        jdbc.update("UPDATE orders SET current_due_minor = 100 WHERE id = ?", orderId);

        var withdrawn = lookup(operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("BLOCKED")).andExpect(jsonPath("$.effect").value("NONE"))
                .andExpect(jsonPath("$.state").value("PREPARED"));
        assertEquals(List.of("PAYMENT_NOT_SETTLED"), blockersOf(withdrawn));
        // the hold stays until the QR Service aborts: a timeout or a withdrawn permit is not an abort
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));

        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    // ---- the command digest -------------------------------------------------------------------------------------------

    @Test
    void theSameCommandDigestReplaysAndAnotherOneIsAConflict() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, DIGEST)).andExpect(status().isOk());

        prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, DIGEST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"));
        prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, "f".repeat(64))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, closures());
    }

    // ---- the exact command --------------------------------------------------------------------------------------------

    @Test
    void everyAnswerEchoesTheDigestOfTheCommandItIsAbout() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();

        prepare(operation).andExpect(jsonPath("$.commandDigest").value(DIGEST));
        lookup(operation).andExpect(jsonPath("$.commandDigest").value(DIGEST));
        confirm(operation, "CLOSED").andExpect(jsonPath("$.commandDigest").value(DIGEST));
    }

    @Test
    void aBlockedAnswerEchoesTheDigestToo() throws Exception {
        prepareRaw(QR, SESSION, settledBody(RESTAURANT, UUID.randomUUID(), "b".repeat(64)))
                .andExpect(jsonPath("$.sourceStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.commandDigest").value("b".repeat(64)));
    }

    @Test
    void askingAboutAKnownOperationWithAnotherDigestIsAConflictAndChangesNothing() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));

        lookupCommand(operation, DIGEST).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PREPARED"));
        lookupCommand(operation, "f".repeat(64)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        confirmCommand(operation, "CLOSED", "f".repeat(64)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        confirmCommand(operation, "NOT_CLOSED", "f".repeat(64)).andExpect(status().isConflict());

        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        confirmCommand(operation, "CLOSED", DIGEST).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.commandDigest").value(DIGEST));
    }

    @Test
    void anOperationNeverSeenEchoesTheDigestOfTheQuestionOnly() throws Exception {
        UUID never = UUID.randomUUID();

        lookupCommand(never, DIGEST).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.commandDigest").value(DIGEST));
        lookup(never).andExpect(status().isOk()).andExpect(jsonPath("$.commandDigest").doesNotExist());
        confirmCommand(never, "NOT_CLOSED", DIGEST).andExpect(status().isOk())
                .andExpect(jsonPath("$.commandDigest").value(DIGEST));
        assertEquals(0, closures());
    }

    @Test
    void aMalformedDigestIsRefused() throws Exception {
        lookupCommand(UUID.randomUUID(), "not-a-digest").andExpect(status().isUnprocessableEntity());
        confirmCommand(UUID.randomUUID(), "NOT_CLOSED", "XYZ").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aConfirmationWithoutTheDigestIsRefusedAndNothingChanges() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));

        for (String outcome : new String[]{"CLOSED", "NOT_CLOSED"}) {
            // for the operation that is held, and for one this service never saw (whose answer needs the digest most)
            for (UUID id : new UUID[]{operation, UUID.randomUUID()}) {
                mvc.perform(post(preparations(SESSION) + "/" + id + "/confirmation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toJson(Map.of("restaurantId", RESTAURANT, "outcome", outcome)))
                                .header(HttpHeaders.AUTHORIZATION, QR))
                        .andExpect(status().isUnprocessableEntity());
            }
        }

        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        assertEquals(1, closures());
    }

    // ---- the receipt --------------------------------------------------------------------------------------------------

    @Test
    void theReceiptOfTheLocalResultIsStored() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", receipt("SETTLED_CLOSE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        var row = jdbc.queryForMap("SELECT receipt_session_version, receipt_table_version, receipt_expired_guests,"
                + " receipt_completed_at FROM table_session_closures WHERE operation_id = ?", operation);
        assertEquals(3L, row.get("receipt_session_version"));
        assertEquals(7L, row.get("receipt_table_version"));
        assertEquals(2, row.get("receipt_expired_guests"));
        assertNotNull(row.get("receipt_completed_at"));
    }

    @Test
    void aReceiptIsOptional() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM table_session_closures WHERE operation_id = ?"
                + " AND receipt_completed_at IS NULL", Integer.class, operation));
    }

    @Test
    void aReceiptForAnotherEffectThanTheOneThatWasPermittedIsRefusedAndNothingChanges() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", receipt("IDLE_MAIN")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_RECEIPT_MISMATCH"));

        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        assertEquals(0, count("table_session_closure_drifts"));
    }

    @Test
    void anInvalidReceiptIsRefused() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", Map.of("effect", "SETTLED_CLOSE",
                "sessionVersion", -1, "tableVersion", 7, "completedAt", Instant.now(), "expiredGuests", 0))
                .andExpect(status().isUnprocessableEntity());
        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", Map.of("effect", "SOMETHING_ELSE",
                "sessionVersion", 1, "tableVersion", 7, "completedAt", Instant.now(), "expiredGuests", 0))
                .andExpect(status().is4xxClientError());

        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
    }

    // ---- the abort ----------------------------------------------------------------------------------------------------

    @Test
    void abortingAnOperationThatWasNeverAuthorisedIsASafeNothingToAbort() throws Exception {
        UUID operation = UUID.randomUUID();
        // BLOCKED: the Order Service stores nothing, and the QR Service still has to be able to abort it for good
        prepare(operation).andExpect(jsonPath("$.sourceStatus").value("BLOCKED"));

        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED"));
        confirm(operation, "NOT_CLOSED").andExpect(status().isOk());
        confirm(operation, "CLOSED").andExpect(status().isNotFound());
        assertEquals(0, closures());
    }
}
