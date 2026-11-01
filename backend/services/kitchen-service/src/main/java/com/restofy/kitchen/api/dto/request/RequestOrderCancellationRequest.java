package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.CancellationReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of a whole-order cancellation request. Only these fields are read: the restaurant, the user and the time are
 * never taken from the body. The reason code is MANDATORY (unlike the optional reason of a product availability
 * change); the text is an optional note, a blank one counts as none. The text is never sent to the Order service.
 *
 * <p>{@code productId} and {@code orderItemId} are the stock reference of an {@code OUT_OF_STOCK} request: they must
 * point at a line of THIS order and are rejected with any other reason (checked against the order's own snapshot).
 */
public record RequestOrderCancellationRequest(
		@NotNull CancellationReason reasonCode,
		@Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String reasonText,
		UUID productId,
		UUID orderItemId) {

	/** Surrounding blanks are dropped and a blank text counts as none. */
	public RequestOrderCancellationRequest {
		reasonText = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();
	}

	/** A request without a stock reference. */
	public RequestOrderCancellationRequest(CancellationReason reasonCode, String reasonText) {
		this(reasonCode, reasonText, null, null);
	}
}
