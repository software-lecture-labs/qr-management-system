package com.restofy.kitchen.application.contract;

import java.util.List;

/** One page of closed products and the total number of closed products matching the query. */
public record ClosedProductsPage(List<ClosedProduct> items, long totalElements) {
}
