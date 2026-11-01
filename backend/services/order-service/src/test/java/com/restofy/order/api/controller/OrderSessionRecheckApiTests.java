package com.restofy.order.api.controller;

import com.restofy.order.application.mapper.OrderMapper;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The QR session is asked again at the moment of ordering. The request-time check can be stale: the session may
 * be closed between the cart review and the order, so the answer of the second call decides.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderSessionRecheckApiTests {

    private static final String TOKEN = "recheck-token";
    private static final GuestSessionContext ACTIVE = DevFixtures.guestA1(SessionState.ACTIVE);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private QrSessionPort qr;
    @MockitoSpyBean
    private OrderMapper orderMapper;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.execute("TRUNCATE order_status_history");
        jdbc.execute("TRUNCATE table_session_closure_drifts, table_session_closures");
        for (String table : new String[]{"outbox_deliveries", "outbox_events", "idempotency_records",
                "order_item_options", "order_items", "orders", "order_number_counters", "carts"}) {
            jdbc.update("DELETE FROM " + table);
        }
        when(qr.validateGuestSession(anyString())).thenReturn(Optional.of(ACTIVE));
        when(qr.currentSessionState(any(), any(), any())).thenReturn(Optional.of(SessionState.ACTIVE));
        mvc.perform(post("/api/v1/carts/current/items").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"productId\": \"" + DevFixtures.SALAD + "\", \"quantity\": 1}"))
                .andExpect(status().isOk());
    }

    private ResultActions order() throws Exception {
        var cart = mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andReturn().getResponse().getContentAsString();
        var node = new JsonMapper().readTree(cart);
        return mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expectedCartVersion\": " + node.path("version").asLong()
                        + ", \"expectedTotalMinor\": " + node.path("totals").path("grossMinor").asLong() + "}"));
    }

    private void assertNothingWasOrdered() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
        // adding the item to the cart recorded a cart-activity notification; no BUSINESS event of an order exists
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type <> 'CartActivityRecorded'", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    @Test
    void sessionClosedAfterTheRequestStartedBlocksTheOrder() throws Exception {
        when(qr.currentSessionState(any(), any(), any())).thenReturn(Optional.of(SessionState.CLOSED));
        order().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertNothingWasOrdered();
    }

    @Test
    void expiredSessionBlocksTheOrder() throws Exception {
        when(qr.currentSessionState(any(), any(), any())).thenReturn(Optional.of(SessionState.EXPIRED));
        order().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertNothingWasOrdered();
    }

    @Test
    void aSessionPairTheQrServiceDoesNotKnowIsTreatedAsNotActive() throws Exception {
        when(qr.currentSessionState(any(), any(), any())).thenReturn(Optional.empty());
        order().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertNothingWasOrdered();
    }

    @Test
    void qrOutageAtOrderTimeFailsClosed() throws Exception {
        when(qr.currentSessionState(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable"));
        order().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertNothingWasOrdered();
    }

    @Test
    void theActiveSessionIsAskedForExactlyTheResolvedIdsAndTheOrderSucceeds() throws Exception {
        order().andExpect(status().isCreated());
        verify(qr, times(1)).currentSessionState(DevFixtures.RESTAURANT_A, DevFixtures.TABLE_SESSION_A1,
                DevFixtures.GUEST_A1);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
    }

    @Test
    void rejectedRequestsDoNotBotherTheQrServiceAgain() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedCartVersion\": 0, \"expectedTotalMinor\": 1}"))
                .andExpect(status().isConflict());
        verify(qr, never()).currentSessionState(any(), any(), any());
    }

    /**
     * KNOWN LIMITATION, documented on purpose. The Order Service re-asks the QR Service just before it commits, but
     * the two services have separate transactions: if the table is closed after that answer and before the commit,
     * the order is still accepted. Closing this gap needs the QR-side admission reservation / fencing described in
     * docs/architecture.md section 5.1, which does not exist yet. This test pins the current behaviour so nobody
     * mistakes the recheck for an atomic guarantee; it is NOT a desired behaviour.
     */
    @Test
    void knownLimitation_aSessionClosedAfterTheRecheckButBeforeTheCommitStillGetsItsOrder() throws Exception {
        AtomicReference<SessionState> stateAtQr =
                new AtomicReference<>(SessionState.ACTIVE);
        when(qr.currentSessionState(any(), any(), any())).thenAnswer(call -> Optional.of(stateAtQr.get()));
        Mockito.doAnswer(call -> {
            stateAtQr.set(SessionState.CLOSED);   // the waiter closes the table while the checkout transaction runs
            return call.callRealMethod();
        }).when(orderMapper).toPlacedEvent(any(), any(), any(), any());

        order().andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class),
                "documented gap: no cross-service atomicity without QR fencing");

        // from now on the closed session is seen and nothing further can be ordered
        Mockito.reset(orderMapper);
        mvc.perform(post("/api/v1/carts/current/items").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"productId\": \"" + DevFixtures.SALAD + "\", \"quantity\": 1}"))
                .andExpect(status().isOk());
        order().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
    }
}
