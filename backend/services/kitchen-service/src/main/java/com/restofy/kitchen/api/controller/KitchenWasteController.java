package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.RecordWasteRequest;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.api.dto.response.WasteRecordResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.api.support.QueryParams;
import com.restofy.kitchen.application.service.WasteRecordService;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/kitchen/waste")
public class KitchenWasteController {

	private final WasteRecordService waste;

	public KitchenWasteController(WasteRecordService waste) {
		this.waste = waste;
	}

	/**
	 * Records a manual waste entry. KITCHEN role only (ADMIN reads, never writes); the restaurant, the user and the
	 * time come from the verified principal and the server, never from the body. The required {@code Idempotency-Key}
	 * makes a retry safe: the first call is 201, the same entry sent again is 200 with the stored entry and no second
	 * row, the same key for a different entry is 409. The entry changes no stock quantity and closes no product.
	 */
	@PostMapping
	public ResponseEntity<WasteRecordResponse> create(@AuthenticationPrincipal KitchenPrincipal principal,
			@RequestHeader(IdempotencyKey.HEADER) List<String> commandIdHeader, @Valid @RequestBody RecordWasteRequest request) {
		WasteRecordService.Recorded recorded = waste.create(principal.restaurantId(), principal.userId(),
				IdempotencyKey.parse(commandIdHeader), request);
		return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(WasteRecordResponse.from(recorded.wasteRecord()));
	}

	/**
	 * The restaurant's waste entries, newest first. KITCHEN and ADMIN may read. {@code recordedFrom} (inclusive) and
	 * {@code recordedTo} (exclusive) are optional ISO-8601 instants, {@code productId} an optional UUID. Every
	 * optional parameter follows the same rule: absent uses the default, present but empty or invalid is 400.
	 */
	@GetMapping
	public PageResponse<WasteRecordResponse> list(@AuthenticationPrincipal KitchenPrincipal principal,
			@RequestParam(required = false) String recordedFrom, @RequestParam(required = false) String recordedTo,
			@RequestParam(required = false) String productId, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		Instant from = QueryParams.parseInstant("recordedFrom", recordedFrom);
		Instant to = QueryParams.parseInstant("recordedTo", recordedTo);
		if (from != null && to != null && !from.isBefore(to)) {
			throw QueryParams.badRequest("recordedFrom must be before recordedTo");
		}
		return waste.list(principal.restaurantId(), from, to, QueryParams.parseUuid("productId", productId),
				QueryParams.parseInt("page", page, 0, 0, Integer.MAX_VALUE),
				QueryParams.parseInt("size", size, QueryParams.DEFAULT_SIZE, 1, QueryParams.MAX_SIZE));
	}
}
