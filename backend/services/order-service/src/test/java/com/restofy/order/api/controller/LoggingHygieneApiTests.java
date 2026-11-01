package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Guest tokens and guest-written text must never reach the logs, on success or on any failure path. */
@ExtendWith(OutputCaptureExtension.class)
class LoggingHygieneApiTests extends AbstractCartApiTest {

    private static final String SECRET_NOTE = "allergic to sesame - private note 7f3a";
    private static final String UNKNOWN_TOKEN = "unknown-token-should-not-appear-in-logs";

    @Test
    void tokensAndGuestTextAreNeverLoggedOnAnyPath(CapturedOutput output) throws Exception {
        String a1 = DevFixtures.TOKEN_A1;
        // rejected credentials
        getCart(UNKNOWN_TOKEN).andExpect(status().isUnauthorized());
        getCart("x".repeat(600)).andExpect(status().isUnauthorized());
        // successful writes carrying guest text
        addItem(a1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", SECRET_NOTE)).andExpect(status().isOk());
        String key = newKey();
        Map<String, Object> body = confirmedCheckout(a1);
        // a rejected and an accepted order, and a replay
        placeOrder(a1, newKey(), Map.of("expectedCartVersion", body.get("expectedCartVersion"),
                "expectedTotalMinor", 1, "note", SECRET_NOTE)).andExpect(status().isConflict());
        placeOrder(a1, key, body).andExpect(status().isCreated());
        placeOrder(a1, key, body).andExpect(status().isCreated());
        // closed session
        qr.register(a1, DevFixtures.guestA1(GuestSessionContext.SessionState.CLOSED));
        addItem(a1, Map.of("productId", DevFixtures.SALAD, "quantity", 1)).andExpect(status().isConflict());

        String logs = output.getAll();
        assertTrue(logs.contains("Order placed:"), "the audit line exists, so this test really inspected the logs");
        for (String secret : new String[]{a1, DevFixtures.TOKEN_A2, DevFixtures.TOKEN_B1, UNKNOWN_TOKEN, "Bearer ",
                SECRET_NOTE, "sesame"}) {
            assertFalse(logs.contains(secret), "logs must not contain: " + secret);
        }
    }
}
