package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cart limits. The DB hard-caps a single line quantity at 1000, so {@code maxItemQuantity} cannot exceed that.
 */
@ConfigurationProperties("restofy.cart")
public record CartProperties(@DefaultValue("50") int maxItemQuantity, @DefaultValue("50") int maxLines) {

    private static final int DB_QUANTITY_CAP = 1000;

    public CartProperties {
        if (maxItemQuantity < 1 || maxItemQuantity > DB_QUANTITY_CAP) {
            throw new IllegalArgumentException("restofy.cart.max-item-quantity must be within 1.." + DB_QUANTITY_CAP);
        }
        if (maxLines < 1) {
            throw new IllegalArgumentException("restofy.cart.max-lines must be at least 1");
        }
    }
}
