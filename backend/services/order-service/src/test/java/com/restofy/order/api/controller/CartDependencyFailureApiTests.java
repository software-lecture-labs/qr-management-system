package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the API does when the QR or Menu service cannot be reached: it fails closed with 503, never "valid". */
@SpringBootTest
@AutoConfigureMockMvc
class CartDependencyFailureApiTests {

    private static final String TOKEN = "any-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private QrSessionPort qr;
    @MockitoBean
    private MenuCatalogPort menu;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM carts");
    }

    private void sessionIsActive() {
        when(qr.validateGuestSession(TOKEN)).thenReturn(Optional.of(DevFixtures.guestA1(SessionState.ACTIVE)));
    }

    @Test
    void qrServiceOutageIsA503AndNeverAuthenticates() throws Exception {
        when(qr.validateGuestSession(anyString()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable"));

        mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void unexpectedQrFailureIsAlsoFailClosedWithoutLeakingDetails() throws Exception {
        when(qr.validateGuestSession(anyString())).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host"));

        mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(result -> assertEquals(false,
                        result.getResponse().getContentAsString().contains("secret-host")));
    }

    @Test
    void menuServiceOutageBlocksAddingAndViewingNonEmptyCarts() throws Exception {
        sessionIsActive();
        when(menu.findProducts(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Menu catalog is currently unavailable"));

        mvc.perform(post("/api/v1/carts/current/items").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": 1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));

        // an empty cart needs no catalog, so viewing it still works
        mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void unexpectedServerErrorsAreGenericAndCorrelated() throws Exception {
        sessionIsActive();
        when(menu.findProducts(any(), any())).thenThrow(new IllegalStateException("select * from secrets"));

        mvc.perform(post("/api/v1/carts/current/items").header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").header("X-Correlation-Id", "trace-123")
                        .content("{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": 1}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value("trace-123"))
                .andExpect(result -> assertEquals(false,
                        result.getResponse().getContentAsString().contains("secrets")));
    }

    @Test
    void sessionStateIsReadFromTheQrServiceOnEveryRequest() throws Exception {
        GuestSessionContext active = DevFixtures.guestA1(SessionState.ACTIVE);
        GuestSessionContext closed = DevFixtures.guestA1(SessionState.CLOSED);
        when(qr.validateGuestSession(TOKEN)).thenReturn(Optional.of(active)).thenReturn(Optional.of(closed));

        mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.sessionActive").value(true));
        mvc.perform(get("/api/v1/carts/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.sessionActive").value(false));
    }
}
