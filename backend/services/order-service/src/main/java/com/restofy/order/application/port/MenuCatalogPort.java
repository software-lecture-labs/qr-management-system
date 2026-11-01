package com.restofy.order.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Contract with the Menu Service (product/price/tax authority). Results are scoped to the given restaurant:
 * a product of another restaurant is simply absent from the result.
 *
 * <p>Prices in {@link ProductSnapshot} are tax-inclusive gross amounts in integer minor units. An
 * implementation that cannot supply that (e.g. net-priced products) must fail rather than guess.
 */
public interface MenuCatalogPort {

    /** Returns the known products among {@code productIds}; unknown ids are omitted. */
    Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds);
}
