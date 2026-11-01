package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A guest session may raise only a few new calls per window. The limit is small here (2 per 60 seconds) so the
 * tests need few requests; the production defaults live in application.properties. QR is the local HTTP stub of
 * the DRAFT contract.
 */
@TestPropertySource(properties = {"restofy.calls.guest-rate-limit.max-new-calls=2",
        "restofy.calls.guest-rate-limit.window-seconds=60"})
class GuestCallRateLimitTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private FakeBackends.Table table;
    private FakeBackends.Guest guest;

    @BeforeEach
    void occupiedTableWithAGuest() {
        table = addTable("12", "OCCUPIED", SESSION);
        guest = addGuest(table);
    }

    private int count(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM " + name, Integer.class);
    }

    @Test
    void aNewCallBeyondTheLimitIsRefusedWithRetryAfterAndWritesNothing() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());

        var refused = raiseCall(guest, "BILL")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CALL_RATE_LIMITED"))
                .andReturn().getResponse();

        long retryAfter = Long.parseLong(refused.getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After is within the window: " + retryAfter);
        assertEquals(2, count("service_requests"));
        assertEquals(2, count("service_call_events"));
        assertEquals(2, count("idempotency_records"));
    }

    @Test
    void theReturnOfAnAlreadyActiveCallIsNotANewCallAndIsNotLimited() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());

        raiseCall(guest, "WAITER").andExpect(status().isOk());
        assertEquals(2, count("service_requests"));
    }

    @Test
    void aRepeatOfAFinishedRequestStillReturnsItsFirstAnswerAtTheLimit() throws Exception {
        String key = newKey();
        String body = "{\"type\":\"WAITER\"}";
        raiseCall(guest, key, body).andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());

        raiseCall(guest, key, body).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Retry-After"));
        assertEquals(2, count("service_requests"));
    }

    @Test
    void anotherGuestOfTheSameTableHasItsOwnAllowance() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());
        raiseCall(guest, "BILL").andExpect(status().isTooManyRequests());

        raiseCall(addGuest(table), "BILL").andExpect(status().isCreated());
    }

    @Test
    void callsOlderThanTheWindowNoLongerCount() throws Exception {
        raiseCall(guest, "WAITER").andExpect(status().isCreated());
        raiseCall(guest, "HELP").andExpect(status().isCreated());
        raiseCall(guest, "BILL").andExpect(status().isTooManyRequests());

        jdbc.update("UPDATE service_requests SET created_at = created_at - interval '2 minutes'");

        raiseCall(guest, "BILL").andExpect(status().isCreated());
    }
}
