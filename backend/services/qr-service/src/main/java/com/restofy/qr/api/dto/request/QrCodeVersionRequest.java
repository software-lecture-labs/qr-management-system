package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record QrCodeVersionRequest(@NotNull @PositiveOrZero Long version) {
}
