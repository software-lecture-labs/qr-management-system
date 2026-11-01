package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.OptionAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proposed, versioned contract for "order accepted" (schemaVersion 1). It is not implemented by the Order
 * service yet; it deliberately carries no price or tax data because Kitchen never calculates money.
 */
public record OrderAcceptedMessage(
		@NotNull UUID eventId,
		@NotNull @Min(1) @Max(1) Integer schemaVersion,
		@NotNull Instant occurredAt,
		@Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]*") String correlationId,
		@NotNull UUID restaurantId,
		@NotNull UUID orderId,
		@NotNull @PositiveOrZero Long aggregateVersion,
		@NotBlank @Size(max = 40) String orderNumber,
		@NotNull UUID tableSessionId,
		@NotBlank @Size(max = 40) String tableNumber,
		@NotEmpty @Size(max = 200) List<@NotNull @Valid Item> items) {

	public record Item(
			@NotNull UUID orderItemId,
			@NotNull UUID productId,
			@NotBlank @Size(max = 200) String productName,
			@NotNull @Min(1) @Max(999) Integer quantity,
			@Size(max = 1000) String note,
			@Size(max = 50) List<@NotNull @Valid Option> options) {

		public Item {
			options = options == null ? List.of() : List.copyOf(options);
		}
	}

	public record Option(
			@NotNull OptionAction action,
			@NotNull UUID optionId,
			@NotBlank @Size(max = 150) String name,
			@NotNull @Min(1) @Max(99) Integer quantity,
			@Size(max = 500) String note) {
	}
}
