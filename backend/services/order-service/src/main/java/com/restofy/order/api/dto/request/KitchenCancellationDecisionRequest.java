package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The Kitchen Service's answer to a cancellation request that waited for it. {@code eventId} is the Kitchen's own event
 * id (redelivery never decides twice). {@code decision} is APPROVED or REJECTED; a rejection needs a {@code
 * rejectionCode} (upper-case identifier, for example KITCHEN_DECLINED or ALREADY_COOKED) and may carry a short note.
 */
public record KitchenCancellationDecisionRequest(
        @NotNull UUID eventId, @NotNull UUID restaurantId, @NotNull UUID orderId, @NotNull UUID cancellationId,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @Pattern(regexp = "[A-Z][A-Z0-9_]{2,39}") String rejectionCode,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String rejectionNote) {
}
