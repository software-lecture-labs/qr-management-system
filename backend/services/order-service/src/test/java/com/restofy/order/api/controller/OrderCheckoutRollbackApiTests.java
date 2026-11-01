package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.application.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A failure after the order rows were written must leave nothing behind: no half order, no lost cart. */
class OrderCheckoutRollbackApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @MockitoSpyBean
    private OrderMapper orderMapper;

    /** Fails inside the checkout transaction, after the order rows were already written and flushed. */
    private void eventCreationFails() {
        doThrow(new IllegalStateException("simulated failure after the order was written"))
                .when(orderMapper).toPlacedEvent(any(), any(), any(), any());
    }

    @Test
    void aFailureAfterTheOrderWasWrittenRollsBackTheWholeCheckout() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();

        eventCreationFails();
        placeOrder(A1, key, body)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertEquals(0, count("orders"), "no half-written order");
        assertEquals(0, count("order_items"));
        assertEquals(0, count("order_item_options"));
        assertEquals(0, count("order_status_history"), "no timeline for an order that does not exist");
        assertEquals(0, count("outbox_events"));
        assertEquals(0, count("idempotency_records"), "the key must not be burned by a failed attempt");
        assertEquals(0, count("order_number_counters"), "the order number must not be consumed");
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class), "the cart is not lost");
        assertEquals(1, count("cart_items"));
        getCart(A1).andExpect(jsonPath("$.items[0].quantity").value(2));

        // once the fault is gone the very same request succeeds and gets the first order number
        Mockito.reset(orderMapper);
        placeOrder(A1, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("ORD-000001"));
        assertEquals(1, count("orders"));
        assertEquals(2, count("order_status_history"), "the retried order gets exactly its two initial rows");
        assertEquals(1, count("outbox_events"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    /**
     * The outbox insert itself fails (the event and its deliveries cannot be stored): the order must not exist
     * without its event, so the whole checkout is rolled back and the guest can retry.
     */
    @Test
    void ifTheOutboxRowCannotBeStoredNoOrderIsCreatedEither() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();

        // a real database failure: the outbox table refuses OrderPlaced events for the duration of this test
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT ck_test_block_events"
                + " CHECK (event_type <> 'OrderPlaced') NOT VALID");
        try {
            placeOrder(A1, key, body).andExpect(status().is4xxClientError());

        assertEquals(0, count("orders"), "an order without its event would never reach Kitchen");
        assertEquals(0, count("order_items"));
        assertEquals(0, count("outbox_events"));
        assertEquals(0, count("outbox_deliveries"));
        assertEquals(0, count("idempotency_records"));
        assertEquals(0, count("order_number_counters"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class));

        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT ck_test_block_events");
        }
        placeOrder(A1, key, body).andExpect(status().isCreated()).andExpect(jsonPath("$.orderNo").value("ORD-000001"));
        assertEquals(1, count("orders"));
        assertEquals(1, count("outbox_events"));
    }

    /** The checkout never talks to another service: an unreachable Kitchen/QR/Notification cannot fail an order. */
    @Test
    void placingAnOrderDoesNotDependOnAnyConsumerOfTheEvent() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id WHERE e.event_type = 'OrderPlaced' AND d.status = 'PENDING'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE attempt_count > 0", Integer.class),
                "nothing was attempted, so nothing is claimed delivered");
    }
}
