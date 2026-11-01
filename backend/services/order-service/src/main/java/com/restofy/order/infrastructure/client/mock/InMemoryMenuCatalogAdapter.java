package com.restofy.order.infrastructure.client.mock;

import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOCK of the Menu Service, active only in the {@code dev} and {@code test} profiles. Prices and VAT rates come from
 * {@link DevFixtures} and are fixture values, not real catalog data. Any other profile gets
 * {@link UnavailableMenuCatalogAdapter}.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class InMemoryMenuCatalogAdapter implements MenuCatalogPort {

    private final Map<UUID, Map<UUID, ProductSnapshot>> catalogByRestaurant = new ConcurrentHashMap<>();

    @PostConstruct
    void announce() {
        reset();
        log.warn("MOCK Menu catalog adapter is active (dev/test profile only). This is NOT the real Menu Service.");
    }

    @Override
    public Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds) {
        Map<UUID, ProductSnapshot> restaurantCatalog = catalogByRestaurant.getOrDefault(restaurantId, Map.of());
        Map<UUID, ProductSnapshot> found = new HashMap<>();
        for (UUID productId : productIds) {
            ProductSnapshot snapshot = restaurantCatalog.get(productId);
            if (snapshot != null) {
                found.put(productId, snapshot);
            }
        }
        return found;
    }

    public void put(UUID restaurantId, ProductSnapshot snapshot) {
        catalogByRestaurant.computeIfAbsent(restaurantId, id -> new ConcurrentHashMap<>())
                .put(snapshot.productId(), snapshot);
    }

    public void remove(UUID restaurantId, UUID productId) {
        catalogByRestaurant.getOrDefault(restaurantId, new ConcurrentHashMap<>()).remove(productId);
    }

    /** Restores the default fixtures. */
    public final void reset() {
        catalogByRestaurant.clear();
        put(DevFixtures.RESTAURANT_A, DevFixtures.burgerSnapshot());
        put(DevFixtures.RESTAURANT_A, DevFixtures.saladSnapshot());
        put(DevFixtures.RESTAURANT_B, DevFixtures.saladSnapshot());
    }
}
