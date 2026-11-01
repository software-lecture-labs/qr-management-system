package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Forces a commit between the initial idempotency lookup and the cart read, without timing-based sleeps. */
class CheckoutReplayRaceApiTests extends AbstractCartApiTest {

    @MockitoSpyBean
    private IdempotencyStore idempotency;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void committedResultTakesPrecedenceOverAConsumedOrReplacedCart(boolean differentBody, boolean replaceCart)
            throws Exception {
        String token = DevFixtures.TOKEN_A1;
        addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        var body = confirmedCheckout(token);
        var retryBody = new HashMap<>(body);
        if (differentBody) {
            retryBody.put("note", "different checkout request");
        }
        String key = newKey();
        var firstLookup = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        var pauseFirstLookup = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (pauseFirstLookup.compareAndSet(true, false)) {
                firstLookup.countDown();
                assertTrue(winnerCommitted.await(15, TimeUnit.SECONDS), "the winner must commit before retry resumes");
            }
            return result;
        }).when(idempotency).find(any(), any(), any(), eq(key));

        try (var pool = Executors.newSingleThreadExecutor()) {
            var retry = pool.submit(() -> placeOrder(token, key, retryBody).andReturn().getResponse());
            String winnerId;
            try {
                assertTrue(firstLookup.await(10, TimeUnit.SECONDS), "retry must complete its initial empty lookup");
                var winner = placeOrder(token, key, body).andExpect(status().isCreated()).andReturn().getResponse();
                winnerId = json.readTree(winner.getContentAsString()).path("orderId").asString();
                if (replaceCart) {
                    addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
                    addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
                }
            } finally {
                winnerCommitted.countDown();
            }
            MockHttpServletResponse response = retry.get(10, TimeUnit.SECONDS);
            assertEquals(differentBody ? 409 : 201, response.getStatus(), response.getContentAsString());
            var result = json.readTree(response.getContentAsString());
            if (differentBody) {
                assertEquals("IDEMPOTENCY_CONFLICT", result.path("code").asString());
            } else {
                assertEquals(winnerId, result.path("orderId").asString());
            }
        }
        assertEquals(1, count("orders"));
        assertEquals(1, count("idempotency_records"));
        assertEquals(1, count("outbox_events"));
        assertEquals(2, count("order_status_history"));
        assertEquals(1, jdbc.queryForObject("SELECT last_value FROM order_number_counters", Integer.class));
    }
}
