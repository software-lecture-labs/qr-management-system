package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

/**
 * The Kitchen service ASKS to cancel an order it cannot make (an ingredient ran out, a mistake in the kitchen). This is
 * the Kitchen's own request, not an answer to a request of the Order Service (that is {@code
 * KitchenCancellationDecisionRequest}).
 *
 * <p>{@code eventId} is the Kitchen's own event id: redelivering it never cancels twice. Either {@code cancelAll: true}
 * or {@code items} (lines of the order and how many of their still-active units); Kitchen V1 prepares per order, so the
 * Order Service never assumes anything about the preparation of a single line. A reason is REQUIRED: at least one code
 * of the catalog, and a {@code reasonText} when the code OTHER is used. {@code requestedByStaffId} optionally names the
 * kitchen staff member behind the request (kept for the audit trail, never used for authorisation).
 */
public record KitchenCancellationRequest(
        @NotNull UUID eventId, @NotNull UUID restaurantId, @NotNull UUID orderId, Boolean cancelAll,
        @Size(max = InputLimits.MAX_SELECTIONS) List<CancelOrderRequest.@Valid Item> items,
        @NotEmpty @Size(max = InputLimits.MAX_REASON_CODES)
        List<@NotNull @Size(max = InputLimits.MAX_REASON_CODE_LENGTH) String> reasonCodes,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String reasonText, UUID requestedByStaffId) {
}
