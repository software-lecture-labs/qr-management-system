package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record GuestActivityRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String guestToken,
        @NotNull UUID restaurantId, @NotNull UUID tableSessionId, @NotNull UUID guestSessionId) {
    @Override
    public String toString() {
        return "GuestActivityRequest[guestToken=[REDACTED]]";
    }
}
