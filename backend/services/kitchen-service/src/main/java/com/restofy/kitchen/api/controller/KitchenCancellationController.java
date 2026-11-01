package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.api.dto.response.CancellationRequestResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.application.service.CancellationRequestService;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kitchen/orders")
public class KitchenCancellationController {

	private final CancellationRequestService cancellations;

	public KitchenCancellationController(CancellationRequestService cancellations) {
		this.cancellations = cancellations;
	}

	/**
	 * Asks the Order service to cancel the WHOLE order. KITCHEN role only (ADMIN reads, never writes); the restaurant,
	 * the user and the time come from the verified principal and the server, never from the body. The required
	 * {@code Idempotency-Key} makes a retry safe: the first call is 202 Accepted ("the request is recorded, the Order
	 * service decides"), the same request sent again is 200 with the stored request, the same key for another request is
	 * 409. The answer describes the REQUEST; the order is not cancelled and keeps its preparation status. READY and
	 * CANCELLED orders are 409, and so is a second request while one is still undecided.
	 */
	@PostMapping("/{orderId}/cancellation-requests")
	public ResponseEntity<CancellationRequestResponse> request(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId, @RequestHeader(IdempotencyKey.HEADER) List<String> commandIdHeader,
			@Valid @RequestBody RequestOrderCancellationRequest body) {
		CancellationRequestService.Requested requested = cancellations.request(principal.restaurantId(), principal.userId(),
				IdempotencyKey.parse(commandIdHeader), orderId, body);
		return ResponseEntity.status(requested.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
				.body(CancellationRequestResponse.from(requested.request()));
	}

	/**
	 * The order's cancellation request history, newest first. KITCHEN and ADMIN may read. Every entry is a REQUEST with
	 * its own state (REQUESTED / APPROVED / REJECTED); only an APPROVED one, decided by the Order service, means the
	 * order is cancelled, which is why this list and the order's own status are shown side by side, never merged.
	 */
	@GetMapping("/{orderId}/cancellation-requests")
	public List<CancellationRequestResponse> history(@AuthenticationPrincipal KitchenPrincipal principal, @PathVariable UUID orderId) {
		return cancellations.history(principal.restaurantId(), orderId).stream().map(CancellationRequestResponse::from).toList();
	}
}
