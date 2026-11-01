package com.restofy.order.api.controller;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the internal endpoint does when service identity cannot be verified: fail closed, and ask nobody else. */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceAuthFailClosedApiTests {

    private static final String PATH = "/api/v1/internal/order-events/kitchen";
    private static final String BODY = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"restaurantId\":\""
            + UUID.randomUUID() + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"kitchenStatus\":\"PREPARING\"}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ServiceIdentityPort services;
    @MockitoBean
    private AuthContextPort auth;
    @MockitoBean
    private QrSessionPort qr;

    private ResultActions call(String authorization) throws Exception {
        var request = post(PATH).contentType("application/json").content(BODY);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    @Test
    void anOutageOfTheIdentityVerifierIsA503AndAuthenticatesNobody() throws Exception {
        when(services.resolveService(anyString())).thenThrow(
                new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Service authentication is currently unavailable"));
        call("Service any-credential").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void everyInternalEndpointIsFailClosedWhenTheVerifierIsDown() throws Exception {
        when(services.resolveService(anyString())).thenThrow(
                new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Service authentication is currently unavailable"));
        for (String path : new String[]{"/api/v1/internal/order-events/kitchen", "/api/v1/internal/order-events/payment",
                "/api/v1/internal/order-events/kitchen-cancellation",
                "/api/v1/internal/order-events/kitchen-cancellation-request"}) {
            var request = post(path).contentType("application/json").content("{}").header("Authorization",
                    "Service any-credential");
            mvc.perform(request).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        }
    }

    @Test
    void anUnexpectedFailureIsAlsoFailClosedWithoutLeakingDetails() throws Exception {
        when(services.resolveService(anyString())).thenThrow(new IllegalStateException("jdbc:postgresql://svc-secret"));
        call("Service any-credential").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("svc-secret")));
    }

    @Test
    void anUnknownCredentialIsA401() throws Exception {
        when(services.resolveService(anyString())).thenReturn(Optional.empty());
        call("Service unknown").andExpect(status().isUnauthorized());
    }

    @Test
    void eachSchemeIsOnlyOfferedToItsOwnVerifier() throws Exception {
        when(services.resolveService(anyString())).thenReturn(Optional.empty());
        call("Service svc-credential").andReturn();
        verify(services).resolveService("svc-credential");
        verifyNoInteractions(auth, qr);

        Mockito.clearInvocations(services, auth, qr);
        call("Staff staff-credential").andReturn();
        verify(auth).resolveStaff("staff-credential");
        verifyNoInteractions(services, qr);

        Mockito.clearInvocations(services, auth, qr);
        call("Bearer guest-token").andReturn();
        verify(qr).validateGuestSession("guest-token");
        verifyNoInteractions(services, auth);
    }

    @Test
    void anonymousRequestsConsultNobody() throws Exception {
        call(null).andExpect(status().isUnauthorized());
        verifyNoInteractions(services, auth, qr);
    }
}
