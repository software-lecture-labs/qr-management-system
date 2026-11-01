package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Fail-closed stand-in used outside the dev/test profiles until the real Menu Service adapter exists. */
@Component
@Profile("!dev & !test")
public class UnavailableMenuCatalogAdapter implements MenuCatalogPort {

    @Override
    public Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Menu catalog is currently unavailable");
    }
}
