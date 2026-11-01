package com.restofy.qr.api.dto.response;

public record OpenedGuestSessionResponse(TableSessionResponse tableSession, GuestSessionResponse guestSession, String guestToken) {
    @Override
    public String toString() {
        return "OpenedGuestSessionResponse[guestToken=[REDACTED]]";
    }
}
