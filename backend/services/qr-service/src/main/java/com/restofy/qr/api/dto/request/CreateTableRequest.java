package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.*;

public record CreateTableRequest(
        @NotBlank @Size(max = 40) String tableNumber,
        @Size(max = 120) String displayName,
        @Positive Integer capacity) {
}
