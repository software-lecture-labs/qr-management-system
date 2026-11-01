package com.restofy.waiter.infrastructure.client.qr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QrGuestVerifyRequestTests {

    @Test
    void theCredentialNeverAppearsInThePrintedForm() {
        QrGuestVerifyRequest request = new QrGuestVerifyRequest("super-secret-guest-token");
        assertFalse(request.toString().contains("super-secret"));
        assertEquals("QrGuestVerifyRequest[guestToken=<redacted>]", request.toString());
        assertEquals("super-secret-guest-token", request.guestToken(), "The value itself is still sent to QR");
    }
}
