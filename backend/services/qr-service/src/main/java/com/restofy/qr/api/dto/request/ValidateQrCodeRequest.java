package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ValidateQrCodeRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String qrToken) {
    @Override
    public String toString() {
        return "ValidateQrCodeRequest[qrToken=[REDACTED]]";
    }
}
