package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.*;

public record UpdateTableRequest(
        @NotBlank @Size(max = 40) String tableNumber,
        @Size(max = 120) String displayName,
        @Positive Integer capacity,
        @NotNull @PositiveOrZero Long version) {
}
