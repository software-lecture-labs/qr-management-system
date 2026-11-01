package com.restofy.waiter.infrastructure.client.qr;

/** Body of the guest verification request. The token is a credential: it is never logged or printed. */
record QrGuestVerifyRequest(String guestToken) {

    @Override
    public String toString() {
        return "QrGuestVerifyRequest[guestToken=<redacted>]";
    }
}
