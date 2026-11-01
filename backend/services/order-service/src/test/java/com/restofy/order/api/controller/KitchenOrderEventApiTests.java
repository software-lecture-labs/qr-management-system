package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 3: the Kitchen service reports preparation status; only it may, safely, repeatedly and out of order. */
class KitchenOrderEventApiTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final UUID RESTAURANT = DevFixtures.RESTAURANT_A;

    private int inboxRows() {
        return count("inbox_events");
    }

    // ---- who may call -----------------------------------------------------------------------------------------------

    @Test
    void onlyAVerifiedKitchenServiceMayCall() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Map<String, Object> body = kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING");

        kitchenEvent(null, body).andExpect(status().isUnauthorized());
        kitchenEvent(service("not-a-known-credential"), body).andExpect(status().isUnauthorized());
        kitchenEvent("Service ", body).andExpect(status().isUnauthorized());
        // other kinds of credential never act as a service
        kitchenEvent(guest(A1), body).andExpect(status().isForbidden());
        kitchenEvent(staff(DevFixtures.STAFF_KITCHEN_A), body).andExpect(status().isForbidden());
        kitchenEvent(staff(DevFixtures.STAFF_ADMIN_A), body).andExpect(status().isForbidden());
        kitchenEvent(service(DevFixtures.STAFF_ADMIN_A), body).andExpect(status().isUnauthorized());
        kitchenEvent(service(DevFixtures.TOKEN_A1), body).andExpect(status().isUnauthorized());
        // a real service credential, but of the wrong service
        kitchenEvent(service(InMemoryServiceIdentityAdapter.PAYMENT_CREDENTIAL), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // identity hints in headers open nothing
        mvc.perform(MockMvcRequestBuilders.post(KITCHEN_EVENTS)
                        .contentType("application/json").content(toJson(body)).header("X-Service", "KITCHEN")
                        .header("X-Role", "KITCHEN"))
                .andExpect(status().isUnauthorized());

        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(0, inboxRows());
    }

    @Test
    void aServiceCredentialOpensNoPublicEndpoint() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        getWith(service(KITCHEN_CREDENTIAL), "/api/v1/orders/" + orderId).andExpect(status().isForbidden());
        getWith(service(KITCHEN_CREDENTIAL), "/api/v1/orders").andExpect(status().isForbidden());
        getWith(service(KITCHEN_CREDENTIAL), "/api/v1/carts/current").andExpect(status().isForbidden());
        patchStatus(service(KITCHEN_CREDENTIAL), orderId, "PREPARING").andExpect(status().isForbidden());
    }

    @Test
    void theKitchenServiceCannotUseThePaymentPathAndPaymentCannotUseTheKitchenPath() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        paymentEvent(service(KITCHEN_CREDENTIAL), paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY",
                Instant.now())).andExpect(status().isForbidden());
        kitchenEvent(service(InMemoryServiceIdentityAdapter.PAYMENT_CREDENTIAL),
                kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING")).andExpect(status().isForbidden());
        assertEquals("NOT_REQUESTED", paymentOf(orderId));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    // ---- applying events --------------------------------------------------------------------------------------------

    @Test
    void thePreparationEventsMoveTheOrderAndAreRecordedAsTheKitchenService() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID preparingEvent = UUID.randomUUID();
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(preparingEvent, RESTAURANT, orderId, "PREPARING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"));
        kitchenEventFor(orderId, "READY").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));

        assertEquals("READY", fulfillmentOf(orderId));
        assertEquals(List.of("-" + ">RECEIVED|GUEST|CHECKOUT", "RECEIVED>PREPARING|SERVICE|KITCHEN_EVENT",
                "PREPARING>READY|SERVICE|KITCHEN_EVENT"), fulfillmentHistory(orderId));
        assertEquals(preparingEvent.toString(), jdbc.queryForObject("SELECT source_event_id::text FROM"
                + " order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", String.class, orderId));
        assertEquals("KITCHEN", jdbc.queryForObject("SELECT actor_id FROM order_status_history WHERE order_id = ?"
                + " AND to_status = 'READY'", String.class, orderId));
        assertEquals(2, inboxRows());
        assertEquals(2, changeEvents(orderId));
        assertEquals(List.of("NOTIFICATION", "WAITER"), destinationsOfChangeTo(orderId, "READY"),
                "the Kitchen is not echoed its own change");
    }

    @Test
    void aRedeliveredEventReturnsTheOriginalOutcomeAndChangesNothing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();
        var body = kitchenBody(eventId, RESTAURANT, orderId, "PREPARING");
        kitchenEvent(service(KITCHEN_CREDENTIAL), body).andExpect(jsonPath("$.duplicate").value(false));
        long version = versionOf(orderId);

        for (int i = 0; i < 3; i++) {
            kitchenEvent(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("APPLIED"))
                    .andExpect(jsonPath("$.duplicate").value(true));
        }
        assertEquals(version, versionOf(orderId));
        assertEquals(2, fulfillmentHistory(orderId).size());
        assertEquals(1, changeEvents(orderId));
        assertEquals(1, inboxRows());
    }

    @Test
    void aRedeliveryIsRecognisedEvenAfterTheOrderMovedOn() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();
        var body = kitchenBody(eventId, RESTAURANT, orderId, "PREPARING");
        kitchenEvent(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk());
        kitchenEventFor(orderId, "READY").andExpect(status().isOk());
        kitchenEvent(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("READY"));
        assertEquals("READY", fulfillmentOf(orderId));
    }

    @Test
    void aLateOrRepeatedStatusUnderANewEventIdIsAcknowledgedWithoutEffect() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        kitchenEventFor(orderId, "PREPARING").andExpect(jsonPath("$.outcome").value("APPLIED"));
        kitchenEventFor(orderId, "READY").andExpect(jsonPath("$.outcome").value("APPLIED"));
        long version = versionOf(orderId);

        // out of order: PREPARING arrives again after READY; and the Kitchen's initial WAITING; and READY twice
        for (String late : new String[]{"PREPARING", "WAITING", "READY"}) {
            kitchenEventFor(orderId, late).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"))
                    .andExpect(jsonPath("$.duplicate").value(false))
                    .andExpect(jsonPath("$.fulfillmentStatus").value("READY"));
        }
        assertEquals(version, versionOf(orderId));
        assertEquals(3, fulfillmentHistory(orderId).size(), "only the two real changes are in the history");
        assertEquals(2, changeEvents(orderId));
        assertEquals(5, inboxRows(), "ignored events are remembered too, so their redelivery is recognised");
    }

    @Test
    void aStatusThatWouldSkipAStepIsRefusedAndNotRememberedSoItCanBeRetried() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID readyEvent = UUID.randomUUID();
        var ready = kitchenBody(readyEvent, RESTAURANT, orderId, "READY");

        kitchenEvent(service(KITCHEN_CREDENTIAL), ready).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.details.allowedNext").value("PREPARING"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(0, inboxRows());

        // the missing step arrives, then the very same READY event is retried and now succeeds
        kitchenEventFor(orderId, "PREPARING").andExpect(status().isOk());
        kitchenEvent(service(KITCHEN_CREDENTIAL), ready).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false));
        assertEquals("READY", fulfillmentOf(orderId));
    }

    @Test
    void theKitchenNeverMovesAnOrderPastReady() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "SERVED");
        kitchenEventFor(orderId, "PREPARING").andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        kitchenEventFor(orderId, "READY").andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        assertEquals("SERVED", fulfillmentOf(orderId), "Served orders stay served; READY is the Kitchen's last word");
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "SERVED"))
                .andExpect(status().isUnprocessableContent());
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "COMPLETED"))
                .andExpect(status().isUnprocessableContent());
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "CANCELLED"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anEventForACancelledOrderIsIgnoredNotApplied() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        kitchenEventFor(orderId, "PREPARING").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("CANCELLED"));
        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertEquals(1, fulfillmentHistory(orderId).size());
    }

    // ---- tenant and identity checks ---------------------------------------------------------------------------------

    @Test
    void theOrderMustBelongToTheRestaurantTheEventNames() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        kitchenEvent(service(KITCHEN_CREDENTIAL),
                kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_B, orderId, "PREPARING"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(UUID.randomUUID(), RESTAURANT, UUID.randomUUID(),
                "PREPARING")).andExpect(status().isNotFound());
        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(0, inboxRows());
    }

    @Test
    void anEventIdAlreadyUsedForAnotherOrderIsAConflict() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID eventId = UUID.randomUUID();
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(eventId, RESTAURANT, first, "PREPARING"))
                .andExpect(status().isOk());
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(eventId, RESTAURANT, second, "PREPARING"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals("RECEIVED", fulfillmentOf(second));
    }

    @Test
    void theBodyIsValidatedStrictly() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var cred = service(KITCHEN_CREDENTIAL);
        kitchenEvent(cred, kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "DONE"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.parameter").value("kitchenStatus"));
        for (String missing : new String[]{"eventId", "restaurantId", "orderId", "kitchenStatus"}) {
            var body = kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING");
            body.remove(missing);
            kitchenEvent(cred, body).andExpect(status().isUnprocessableContent());
        }
        var extra = kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING");
        extra.put("fulfillmentStatus", "COMPLETED");
        kitchenEvent(cred, extra).andExpect(status().isBadRequest());
        kitchenEvent(cred, "not json").andExpect(status().isBadRequest());
        // the status text is case-insensitive
        kitchenEvent(cred, kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "preparing")).andExpect(status().isOk());
        assertEquals("PREPARING", fulfillmentOf(orderId));
    }
}
