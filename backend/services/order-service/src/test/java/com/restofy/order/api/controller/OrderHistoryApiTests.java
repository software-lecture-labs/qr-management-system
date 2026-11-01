package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 1: the append-only status timeline and its access rules. */
class OrderHistoryApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;

    private JsonNode history(String authorization, UUID orderId) throws Exception {
        return json.readTree(getWith(authorization, "/api/v1/orders/" + orderId + "/history")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void insertHistory(UUID orderId, String axis, String from, String to, Instant at, UUID eventId) {
        jdbc.update("INSERT INTO order_status_history (id, restaurant_id, order_id, state_axis, from_status, to_status,"
                        + " actor_type, actor_id, actor_role, source, source_event_id, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'SERVICE', 'KITCHEN', 'KITCHEN', 'SERVICE_EVENT', ?, ?)",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, axis, from, to, eventId, Timestamp.from(at));
    }

    @Test
    void everyOrderStartsWithOneRowPerIndependentStatusAxis() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        JsonNode entries = history(guest(A1), orderId).path("entries");
        assertEquals(2, entries.size());

        JsonNode fulfillment = entries.get(0);
        assertEquals("FULFILLMENT", fulfillment.path("axis").asString());
        assertTrue(fulfillment.path("fromStatus").isNull());
        assertEquals("RECEIVED", fulfillment.path("toStatus").asString());
        assertEquals("GUEST", fulfillment.path("actorType").asString());
        assertEquals("CHECKOUT", fulfillment.path("source").asString());

        JsonNode payment = entries.get(1);
        assertEquals("PAYMENT", payment.path("axis").asString());
        assertEquals("NOT_REQUESTED", payment.path("toStatus").asString());
        assertEquals("SYSTEM", payment.path("actorType").asString());
        assertEquals(orderId.toString(), history(guest(A1), orderId).path("orderId").asString());
    }

    @Test
    void retriesAndReplaysNeverAddHistory() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        var body = confirmedCheckout(A1);
        String key = newKey();
        placeOrder(A1, key, body).andExpect(status().isCreated());
        placeOrder(A1, key, body).andExpect(status().isCreated());
        assertEquals(2, count("order_status_history"));
    }

    @Test
    void onlyAdminsSeeWhoActedAndGuestsSeeNoIdentifiers() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        JsonNode asAdmin = history(staff(DevFixtures.STAFF_ADMIN_A), orderId).path("entries").get(0);
        assertEquals(DevFixtures.GUEST_A1.toString(), asAdmin.path("actorId").asString());

        for (String viewer : new String[]{guest(A1), staff(DevFixtures.STAFF_WAITER_A)}) {
            JsonNode entry = history(viewer, orderId).path("entries").get(0);
            assertTrue(entry.path("actorId").isNull(), "no actor identifier for " + viewer);
            assertEquals("GUEST", entry.path("actorType").asString());
        }
    }

    @Test
    void historyFollowsTheSameAccessRulesAsTheOrderItself() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String path = "/api/v1/orders/" + orderId + "/history";
        getWith(guest(A2), path).andExpect(status().isNotFound());
        getWith(guest(DevFixtures.TOKEN_B1), path).andExpect(status().isNotFound());
        getWith(staff(DevFixtures.STAFF_ADMIN_B), path).andExpect(status().isNotFound());
        getWith(staff(DevFixtures.STAFF_KITCHEN_A), path).andExpect(status().isForbidden());
        getWith(null, path).andExpect(status().isUnauthorized());
        getWith(guest(A1), "/api/v1/orders/" + UUID.randomUUID() + "/history")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void theTimelineOrderIsDeterministicEvenWhenTimestampsCollide() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant same = Instant.now().plusSeconds(60);
        insertHistory(orderId, "FULFILLMENT", "RECEIVED", "PREPARING", same, UUID.randomUUID());
        insertHistory(orderId, "FULFILLMENT", "PREPARING", "READY", same, UUID.randomUUID());
        insertHistory(orderId, "FULFILLMENT", "READY", "SERVED", same, UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            JsonNode entries = history(staff(DevFixtures.STAFF_ADMIN_A), orderId).path("entries");
            assertEquals(5, entries.size());
            assertEquals("PREPARING", entries.get(2).path("toStatus").asString());
            assertEquals("READY", entries.get(3).path("toStatus").asString());
            assertEquals("SERVED", entries.get(4).path("toStatus").asString());
        }
    }

    // ---- the table itself protects the timeline -------------------------------------------------------------------

    @Test
    void historyRowsCanNeverBeChangedOrRemoved() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        for (String statement : new String[]{
                "UPDATE order_status_history SET to_status = 'COMPLETED' WHERE order_id = '" + orderId + "'",
                "UPDATE order_status_history SET actor_type = 'SYSTEM' WHERE order_id = '" + orderId + "'",
                "UPDATE order_status_history SET reason = 'rewritten' WHERE order_id = '" + orderId + "'",
                "DELETE FROM order_status_history WHERE order_id = '" + orderId + "'",
                "DELETE FROM order_status_history"}) {
            DataAccessException error = assertThrows(DataAccessException.class, () -> jdbc.update(statement), statement);
            assertTrue(error.getMessage().contains("append-only"), statement + ": " + error.getMessage());
        }
        assertEquals(2, count("order_status_history"));
    }

    @Test
    void theSameExternalEventCanBeRecordedOnlyOncePerAxis() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID event = UUID.randomUUID();
        insertHistory(orderId, "FULFILLMENT", "RECEIVED", "PREPARING", Instant.now(), event);
        Instant duplicateAt = Instant.now();
        assertThrows(DataIntegrityViolationException.class,
                () -> insertHistory(orderId, "FULFILLMENT", "PREPARING", "READY", duplicateAt, event));
        // the same event id on the other axis is a different fact
        insertHistory(orderId, "PAYMENT", "NOT_REQUESTED", "PENDING", Instant.now(), event);
    }

    @Test
    void statusValuesMustBelongToTheirAxisAndOrdersMustExist() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant occurredAt = Instant.now();
        UUID missingOrderId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class,
                () -> insertHistory(orderId, "FULFILLMENT", "RECEIVED", "PAID", occurredAt, null));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertHistory(orderId, "PAYMENT", "NOT_REQUESTED", "READY", occurredAt, null));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertHistory(orderId, "OTHER", null, "RECEIVED", occurredAt, null));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertHistory(missingOrderId, "FULFILLMENT", null, "RECEIVED", occurredAt, null));
    }
}
