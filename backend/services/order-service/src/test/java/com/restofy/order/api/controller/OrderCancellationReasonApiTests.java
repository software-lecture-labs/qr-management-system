package com.restofy.order.api.controller;

import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 2: the reason is optional - several codes, free text, both, or nothing - and is validated and kept as given. */
class OrderCancellationReasonApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    @Test
    void noReasonAtAllIsAccepted() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancelAll(guest(A1), orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons.length()").value(0))
                .andExpect(jsonPath("$.reasonPending").value(true));
        assertEquals(0, count("order_cancellation_reasons"));
    }

    @Test
    void blankReasonFieldsCountAsNoReason() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelAllWith(List.of(), "   \t ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons.length()").value(0))
                .andExpect(jsonPath("$.reasonPending").value(true));
    }

    @Test
    void severalCodesAndAFreeTextAreAllKeptWithTheirSource() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelAllWith(List.of("CHANGED_MIND", "WAIT_TOO_LONG", "OTHER"),
                "  We have to leave early  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasonPending").value(false))
                .andExpect(jsonPath("$.reasons.length()").value(4))
                .andExpect(jsonPath("$.reasons[0].code").value("CHANGED_MIND"))
                .andExpect(jsonPath("$.reasons[1].code").value("WAIT_TOO_LONG"))
                .andExpect(jsonPath("$.reasons[2].code").value("OTHER"))
                .andExpect(jsonPath("$.reasons[3].text").value("We have to leave early"))
                .andExpect(jsonPath("$.reasons[3].code").doesNotExist())
                .andExpect(jsonPath("$.reasons[*].source", contains("GUEST", "GUEST", "GUEST", "GUEST")))
                .andExpect(jsonPath("$.reasons[0].addedBy.type").value("GUEST"))
                .andExpect(jsonPath("$.reasons[0].addedBy.id").doesNotExist());

        // the audit trail names who gave each reason (an internal session id, never a token) and when
        assertEquals(4, count("order_cancellation_reasons"));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE added_by_type = 'GUEST'"
                + " AND added_by_id = ? AND added_at IS NOT NULL", Integer.class, DevFixtures.GUEST_A1.toString()));
    }

    @Test
    void aFreeTextAloneIsEnough() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelAllWith(null, "Ordered for the wrong table"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0].text").value("Ordered for the wrong table"));
    }

    @Test
    void theReasonIsStoredAsPlainTextAndNeverInterpreted() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String hostile = "<script>alert(1)</script> ' OR 1=1 -- ${jndi:ldap://x} {{7*7}}";

        cancel(guest(A1), orderId, newKey(), cancelAllWith(null, hostile)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons[0].text").value(hostile));
        assertEquals(hostile, jdbc.queryForObject("SELECT reason_text FROM order_cancellation_reasons", String.class));
        assertEquals(1, count("order_cancellations"));
    }

    @Test
    void unknownCodesAreRefusedWithoutEchoingTheInput() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        for (String bad : List.of("NOT_A_REASON", "changed_mind", "Changed_Mind", " OTHER", "OTHER ", "", "DROP TABLE")) {
            cancel(guest(A1), orderId, newKey(), cancelAllWith(List.of(bad), null))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertEquals(0, count("order_cancellations"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    @Test
    void aRepeatedCodeAndTooManyCodesAreRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelAllWith(List.of("OTHER", "OTHER"), null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        List<String> eleven = Arrays.stream(CancellationReasonCode.values()).map(Enum::name).toList();
        List<String> tooMany = new ArrayList<>(eleven);
        tooMany.addAll(eleven);
        cancel(guest(A1), orderId, newKey(), cancelAllWith(tooMany, null)).andExpect(status().isUnprocessableContent());
        // a null element in the list
        cancel(guest(A1), orderId, newKey(), Map.of("cancelAll", true, "reasonCodes",
                Collections.singletonList(null))).andExpect(status().isUnprocessableContent());
        assertEquals(0, count("order_cancellations"));
    }

    @Test
    void aTooLongTextAndControlCharactersAreRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelAllWith(null, "x".repeat(301)))
                .andExpect(status().isUnprocessableContent());
        cancel(guest(A1), orderId, newKey(), cancelAllWith(null, "x".repeat(300))).andExpect(status().isCreated());
    }

    @Test
    void controlCharactersInTheTextAreRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        for (String bad : List.of("line one\nline two", "tab\there", "nul\u0000byte", "bell\u0007", "esc\u001b[31m")) {
            cancel(guest(A1), orderId, newKey(), cancelAllWith(null, bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertEquals(0, count("order_cancellations"));
    }

    @Test
    void aWaiterMayGiveTheReasonWhenCancellingAndItIsMarkedAsStaffs() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancel(WAITER, orderId, newKey(), cancelAllWith(List.of("KITCHEN_ERROR"), "Burnt"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons[*].source", contains("STAFF", "STAFF")))
                .andExpect(jsonPath("$.reasons[0].addedBy.role").value("WAITER"))
                .andExpect(jsonPath("$.reasonPending").value(false));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE added_by_type = 'STAFF'"
                + " AND added_by_role = 'WAITER' AND added_by_id = ?", Integer.class, DevFixtures.USER_WAITER_A.toString()));
    }

    @Test
    void theReasonsAreInTheCancellationEventAndTheReasonIsPartOfTheReplayDigest() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String key = newKey();
        cancel(guest(A1), orderId, key, cancelAllWith(List.of("ORDERED_BY_MISTAKE", "OTHER"), "oops"))
                .andExpect(status().isCreated());

        String payload = payloadOf(orderId, "OrderCancelled");
        assertEquals(true, payload.contains("ORDERED_BY_MISTAKE"), payload);
        assertEquals(true, payload.contains("oops"), payload);
        assertEquals(false, payload.contains(DevFixtures.GUEST_A1.toString()), "no guest session id in an event");
        assertEquals(false, payload.contains(DevFixtures.TOKEN_A1), "no token in an event");
        // the same key with another reason is another request
        cancel(guest(A1), orderId, key, cancelAllWith(List.of("CHANGED_MIND"), "oops"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // the same reasons listed in another order are the same request
        cancel(guest(A1), orderId, key, cancelAllWith(List.of("OTHER", "ORDERED_BY_MISTAKE"), "oops"))
                .andExpect(status().isCreated());
    }
}
