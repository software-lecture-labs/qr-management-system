package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of the availability change. Only these fields are read; the restaurant, the user and the time are never taken
 * from the body (a forged restaurantId, actor or changedAt is simply ignored). The reason is optional: a missing or
 * blank code / text means "no reason given" and is stored and sent as {@code null} - nothing is invented in its place.
 */
public record ChangeProductAvailabilityRequest(
		@NotNull ProductAvailabilityStatus status,
		@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String reasonCode,
		@Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String reasonText) {

	/** A blank code or description counts as none; surrounding blanks of a description are dropped. */
	public ChangeProductAvailabilityRequest {
		reasonCode = reasonCode == null || reasonCode.isBlank() ? null : reasonCode;
		reasonText = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();
	}
}
