package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeTableActiveRequest(
        @NotNull Boolean isActive,
        @NotNull @PositiveOrZero Long version) {
}
