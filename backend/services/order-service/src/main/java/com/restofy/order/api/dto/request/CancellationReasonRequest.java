package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The reason a waiter gives later for a cancellation the customer gave none for. At least one of the two fields is
 * needed; the rules are those of the customer's reason (catalog codes, each once, and/or plain text).
 */
public record CancellationReasonRequest(
        @Size(max = InputLimits.MAX_REASON_CODES) List<@NotNull @Size(max = InputLimits.MAX_REASON_CODE_LENGTH) String> reasonCodes,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String reasonText) {
}
