package com.restofy.order.api.controller;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the API does when the Auth Service cannot be reached: it fails closed with 503 and asks nobody else. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffAuthFailClosedApiTests {

    private static final String PATH = "/api/v1/orders/" + UUID.randomUUID();

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthContextPort auth;
    @MockitoBean
    private QrSessionPort qr;
    // Not used here. It is declared so this class shares one Spring context (and one database connection pool) with
    // ServiceAuthFailClosedApiTests; every extra context holds its own pool and the test database has limited slots.
    @MockitoBean
    private ServiceIdentityPort services;

    @Test
    void anAuthOutageIsA503AndNeverAuthenticatesAnybody() throws Exception {
        when(auth.resolveStaff(anyString())).thenThrow(
                new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable"));
        mvc.perform(get(PATH).header("Authorization", "Staff any-credential"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void anUnexpectedAuthFailureIsAlsoFailClosedWithoutLeakingDetails() throws Exception {
        when(auth.resolveStaff(anyString())).thenThrow(new IllegalStateException("jdbc:postgresql://auth-db-secret"));
        mvc.perform(get(PATH).header("Authorization", "Staff any-credential"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("auth-db-secret")));
    }

    @Test
    void staffCredentialsAreNeverOfferedToTheQrServiceAndGuestTokensNeverToAuth() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Staff some-credential")).andReturn();
        verify(auth).resolveStaff("some-credential");
        verifyNoInteractions(qr);

        clearInvocations(auth, qr);
        mvc.perform(get(PATH).header("Authorization", "Bearer some-guest-token")).andReturn();
        verify(qr).validateGuestSession("some-guest-token");
        verifyNoInteractions(auth);
    }

    @Test
    void anonymousRequestsConsultNobody() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
        verifyNoInteractions(qr);
    }
}
