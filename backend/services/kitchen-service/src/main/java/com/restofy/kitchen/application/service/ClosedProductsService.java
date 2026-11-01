package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.response.ClosedProductResponse;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.application.contract.ClosedProduct;
import com.restofy.kitchen.application.contract.ClosedProductsPage;
import com.restofy.kitchen.application.contract.ClosedProductsQuery;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.application.port.MenuClosedProductsPort;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lists the closed products by asking the Menu service, the only source of truth. Nothing is stored or cached here
 * and no database transaction is involved. If Menu cannot answer reliably the result is an error, never an empty
 * list: "nothing is closed" must only ever mean that Menu said so.
 */
@Service
public class ClosedProductsService {

	private static final Logger log = LoggerFactory.getLogger(ClosedProductsService.class);

	private final MenuClosedProductsPort menu;

	public ClosedProductsService(MenuClosedProductsPort menu) {
		this.menu = menu;
	}

	public PageResponse<ClosedProductResponse> list(UUID restaurantId, Set<ProductAvailabilityStatus> statuses, int page, int size) {
		ClosedProductsQuery query = new ClosedProductsQuery(restaurantId, Set.copyOf(statuses), page, size);
		MenuClosedProductsPort.Result result = ask(query);
		if (result.page() == null) {
			throw new ProductAvailabilityException(MenuAvailabilityPort.Kind.UNAVAILABLE, ProductAvailabilityService.safe(result.errorCode()));
		}
		if (!isConsistent(query, result.page())) {
			log.warn("Menu closed-products answer inconsistent: restaurantId={}", restaurantId);
			throw new ProductAvailabilityException(MenuAvailabilityPort.Kind.UNAVAILABLE, ProductAvailabilityService.INCONSISTENT_RESPONSE);
		}
		List<ClosedProductResponse> content = result.page().items().stream().map(ClosedProductResponse::from).toList();
		long total = result.page().totalElements();
		return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
	}

	/** An exception or a missing answer is "Menu could not be asked", not an empty result. */
	private MenuClosedProductsPort.Result ask(ClosedProductsQuery query) {
		try {
			MenuClosedProductsPort.Result result = menu.list(query);
			return result == null ? MenuClosedProductsPort.Result.unavailable("NO_RESULT") : result;
		} catch (RuntimeException _) {
			return MenuClosedProductsPort.Result.unavailable(ProductAvailabilityService.PORT_EXCEPTION);
		}
	}

	/** Never pass on what contradicts the question: wrong status, too many items, repeated product, wrong order or count. */
	private static boolean isConsistent(ClosedProductsQuery query, ClosedProductsPage page) {
		List<ClosedProduct> items = page.items();
		if (items == null || items.size() > query.size() || page.totalElements() < 0
				|| (!items.isEmpty() && page.totalElements() < (long) query.page() * query.size() + items.size())) {
			return false;
		}
		Set<UUID> seen = new HashSet<>();
		Instant previous = null;
		for (ClosedProduct item : items) {
			if (item == null || item.productId() == null || item.changedAt() == null || !query.statuses().contains(item.status())
					|| !seen.add(item.productId()) || (previous != null && item.changedAt().isAfter(previous))) {
				return false;
			}
			previous = item.changedAt();
		}
		return true;
	}
}
