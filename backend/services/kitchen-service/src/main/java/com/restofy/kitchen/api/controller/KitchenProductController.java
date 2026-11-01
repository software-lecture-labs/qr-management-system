package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.ChangeProductAvailabilityRequest;
import com.restofy.kitchen.api.dto.response.ClosedProductResponse;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.api.dto.response.ProductAvailabilityResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.api.support.QueryParams;
import com.restofy.kitchen.application.service.ClosedProductsService;
import com.restofy.kitchen.application.service.ProductAvailabilityService;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kitchen/products")
public class KitchenProductController {

	private final ProductAvailabilityService availability;
	private final ClosedProductsService closedProducts;

	public KitchenProductController(ProductAvailabilityService availability, ClosedProductsService closedProducts) {
		this.availability = availability;
		this.closedProducts = closedProducts;
	}

	/**
	 * Asks the Menu service to change a product's sales availability (OUT_OF_STOCK, TEMPORARILY_CLOSED, or AVAILABLE
	 * to reopen it). KITCHEN role only (ADMIN reads, never writes); the restaurant and the user come from the verified
	 * principal, never from the body or headers. The required {@code Idempotency-Key} is the command id. 200 only
	 * with the state Menu confirmed; if Menu cannot be reached or is not integrated the outcome is unknown and the
	 * answer is 503 - never a success. Reopening changes availability only: it never publishes a product (see
	 * {@code published} in the answer).
	 */
	@PatchMapping("/{productId}/availability")
	public ProductAvailabilityResponse change(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID productId, @RequestHeader(IdempotencyKey.HEADER) List<String> commandIdHeader,
			@Valid @RequestBody ChangeProductAvailabilityRequest request) {
		return ProductAvailabilityResponse.from(availability.change(principal.restaurantId(), principal.userId(),
				IdempotencyKey.parse(commandIdHeader), productId, request));
	}

	/**
	 * The restaurant's closed products (OUT_OF_STOCK and TEMPORARILY_CLOSED) as the Menu service reports them, newest
	 * closing first. KITCHEN and ADMIN may read. {@code status} defaults to both closed statuses only when the
	 * parameter is absent; present but empty, repeated, unknown or AVAILABLE is 400. {@code page} (0-based) and
	 * {@code size} (1-100, default 20) follow the same rule as every optional parameter: absent uses the default,
	 * present but empty or invalid is 400. The tenant comes from the verified principal. If Menu cannot answer the
	 * result is 503, never an empty list.
	 */
	@GetMapping("/closed")
	public PageResponse<ClosedProductResponse> closed(@AuthenticationPrincipal KitchenPrincipal principal,
			@RequestParam(required = false) List<String> status, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		return closedProducts.list(principal.restaurantId(), parseClosedStatuses(status),
				QueryParams.parseInt("page", page, 0, 0, Integer.MAX_VALUE),
				QueryParams.parseInt("size", size, QueryParams.DEFAULT_SIZE, 1, QueryParams.MAX_SIZE));
	}

	private static Set<ProductAvailabilityStatus> parseClosedStatuses(List<String> status) {
		if (status == null) {
			return EnumSet.of(ProductAvailabilityStatus.OUT_OF_STOCK, ProductAvailabilityStatus.TEMPORARILY_CLOSED);
		}
		if (status.size() == 1) {
			try {
				ProductAvailabilityStatus parsed = ProductAvailabilityStatus.valueOf(status.get(0));
				if (parsed.isClosed()) {
					return EnumSet.of(parsed);
				}
			} catch (IllegalArgumentException _) {
				// falls through to the same 400 as a status that is not a closed one
			}
		}
		throw QueryParams.badRequest("status must be exactly one of OUT_OF_STOCK, TEMPORARILY_CLOSED");
	}
}
