package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.api.support.StrictWholeNumberDeserializer;
import com.restofy.kitchen.domain.enums.WasteReason;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;

/**
 * Body of a manual waste entry. Only these fields are read: the restaurant, the user and the time are never taken from
 * the body. {@code quantity} is a positive whole number of portions (at most {@value #MAX_QUANTITY} per entry): a
 * fraction such as 1.5 or a text such as "2" is rejected, never rounded or converted.
 * {@code orderId} is optional; when given it must be an order of the caller's own restaurant.
 */
public record RecordWasteRequest(
		@NotNull UUID productId,
		@NotNull @Min(1) @Max(MAX_QUANTITY) @JsonDeserialize(using = StrictWholeNumberDeserializer.class) Integer quantity,
		@NotNull WasteReason reasonCode,
		@Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String description,
		UUID orderId) {

	public static final int MAX_QUANTITY = 10_000;

	/** Surrounding blanks are dropped and a blank description counts as none. */
	public RecordWasteRequest {
		description = description == null || description.isBlank() ? null : description.strip();
	}
}
