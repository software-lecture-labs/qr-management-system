package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.security.GuestAuthentication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression evidence for the CSRF review: automatically sent credentials never authorize mutations. */
class HeaderAuthenticationSecurityTests extends AbstractCartApiTest {

    @Test
    void cookiesCannotAuthenticateACartMutation() throws Exception {
        mvc.perform(post(CART + "/items")
                        .cookie(new Cookie("Authorization", guest(DevFixtures.TOKEN_A1)),
                                new Cookie("guest_token", DevFixtures.TOKEN_A1),
                                new Cookie("staff_token", DevFixtures.STAFF_ADMIN_A))
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }

    @Test
    void anExistingHttpSessionCannotAuthenticateACartMutation() throws Exception {
        var guestContext = qr.validateGuestSession(DevFixtures.TOKEN_A1).orElseThrow();
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(new GuestAuthentication(guestContext)));
        mvc.perform(post(CART + "/items").session(session)
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }

    @Test
    void basicAuthenticationCannotAuthenticateACartMutation() throws Exception {
        mvc.perform(post(CART + "/items").header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }
}
