package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.response.KitchenOrderDetailResponse;
import com.restofy.kitchen.api.dto.response.KitchenOrderSummaryResponse;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.api.support.QueryParams;
import com.restofy.kitchen.application.service.KitchenOrderQueryService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.domain.enums.KitchenStatusFilter;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kitchen/orders")
public class KitchenOrderController {

	private final KitchenOrderQueryService queries;
	private final KitchenPreparationService preparation;

	public KitchenOrderController(KitchenOrderQueryService queries, KitchenPreparationService preparation) {
		this.queries = queries;
		this.preparation = preparation;
	}

	/**
	 * Kitchen queue, always ordered {@code received_at ASC, id ASC} (oldest first, FIFO). {@code status} defaults
	 * to WAITING only when the parameter is absent; an empty or unknown value is 400. {@code ACTIVE} means
	 * WAITING + PREPARING. {@code receivedFrom} (inclusive) and {@code receivedTo} (exclusive) are optional
	 * ISO-8601 instants. Every optional parameter follows the same rule: <b>absent</b> uses the default (none for
	 * the dates, page 0, size 20), while <b>present but empty, blank or invalid</b> is 400. Values are never echoed
	 * back. The tenant comes from the verified principal.
	 */
	@GetMapping
	public PageResponse<KitchenOrderSummaryResponse> list(
			@AuthenticationPrincipal KitchenPrincipal principal,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String receivedFrom,
			@RequestParam(required = false) String receivedTo,
			@RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		Instant from = QueryParams.parseInstant("receivedFrom", receivedFrom);
		Instant to = QueryParams.parseInstant("receivedTo", receivedTo);
		if (from != null && to != null && !from.isBefore(to)) {
			throw QueryParams.badRequest("receivedFrom must be before receivedTo");
		}
		return queries.list(principal.restaurantId(), parseStatus(status), from, to,
				QueryParams.parseInt("page", page, 0, 0, Integer.MAX_VALUE),
				QueryParams.parseInt("size", size, QueryParams.DEFAULT_SIZE, 1, QueryParams.MAX_SIZE));
	}

	private static KitchenStatusFilter parseStatus(String status) {
		if (status == null) {
			return KitchenStatusFilter.WAITING;
		}
		try {
			return KitchenStatusFilter.valueOf(status);
		} catch (IllegalArgumentException e) {
			throw QueryParams.badRequest("status must be one of WAITING, PREPARING, READY, CANCELLED, ACTIVE");
		}
	}

	/**
	 * Starts preparation (WAITING -> PREPARING). KITCHEN role only. There is deliberately no request body: the
	 * tenant and the actor come from the verified principal and the time from the server, so nothing the client
	 * sends can influence them. The required {@code Idempotency-Key} (UUID) identifies the command: a verified
	 * repeat of the same command returns 200 without any change; any other command on an order that is already
	 * PREPARING is 409.
	 */
	@PatchMapping("/{orderId}/start-preparation")
	public KitchenOrderDetailResponse startPreparation(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") List<String> commandIdHeader) {
		return preparation.startPreparation(principal.restaurantId(), principal.userId(), IdempotencyKey.parse(commandIdHeader), orderId);
	}

	/**
	 * Finishes preparation (PREPARING -> READY). Same contract as {@code start-preparation}: KITCHEN role only, no
	 * request body, required {@code Idempotency-Key}, tenant and actor from the verified principal. A WAITING order
	 * cannot be marked ready (409).
	 */
	@PatchMapping("/{orderId}/mark-ready")
	public KitchenOrderDetailResponse markReady(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") List<String> commandIdHeader) {
		return preparation.markReady(principal.restaurantId(), principal.userId(), IdempotencyKey.parse(commandIdHeader), orderId);
	}

	/** Read-only detail by Order service id. 404 for unknown ids and for other restaurants' orders alike. */
	@GetMapping("/{orderId}")
	public KitchenOrderDetailResponse get(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId) {
		return queries.get(principal.restaurantId(), orderId);
	}
}
