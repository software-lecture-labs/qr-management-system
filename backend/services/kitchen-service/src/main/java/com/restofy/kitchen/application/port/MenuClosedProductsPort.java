package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.contract.ClosedProductsPage;
import com.restofy.kitchen.application.contract.ClosedProductsQuery;

/**
 * Read side of the Menu boundary: which products are closed right now. The Menu service is the only source of
 * truth, so Kitchen keeps no copy and no cache; an empty list means "Menu says nothing is closed", never "Menu could
 * not be asked". There is NO production adapter yet (Menu has no such endpoint): the default bean answers
 * {@code MENU_NOT_INTEGRATED}.
 *
 * <p>Contract an adapter must honour: only the query's restaurant, only the requested statuses, ordered
 * {@code changedAt DESC, productId ASC} so that paging is deterministic, {@code totalElements} counted over the same
 * filter; any timeout, connection problem, 5xx or unknown answer is {@link Result#unavailable(String)}; no retry of
 * its own.
 */
public interface MenuClosedProductsPort {

	Result list(ClosedProductsQuery query);

	/** Either a page or a short safe error code, never both. */
	record Result(ClosedProductsPage page, String errorCode) {

		public static Result ok(ClosedProductsPage page) {
			return new Result(page, null);
		}

		public static Result unavailable(String errorCode) {
			return new Result(null, errorCode);
		}
	}
}
