package com.restofy.order.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Who may be the SOURCE of the preparation statuses PREPARING and READY. The Order Service row is always the single
 * point where a change is serialised and recorded, but the authority behind these two statuses is the Kitchen
 * Service: in every profile except {@code dev} and {@code test} only its events
 * ({@code POST /api/v1/internal/order-events/kitchen}) may set them, so kitchen staff clicking in some other tool and
 * the Kitchen Service can never race over the same status.
 *
 * <p>In {@code dev} and {@code test} KITCHEN staff may also set them with the PATCH endpoint, as a controlled stand-in
 * until the real Kitchen Service exists (the same rule that confines the mock adapters to those profiles). This is not
 * a runtime option: it cannot be switched on in production.
 */
@Slf4j
@Component
public class FulfillmentAuthorityPolicy {

    private final boolean kitchenStaffMaySetPreparationStatuses;

    public FulfillmentAuthorityPolicy(Environment environment) {
        this.kitchenStaffMaySetPreparationStatuses = environment.acceptsProfiles(Profiles.of("dev", "test"));
        if (kitchenStaffMaySetPreparationStatuses) {
            log.warn("KITCHEN staff may set PREPARING/READY through the order API (dev/test profile only); in other "
                    + "profiles only the Kitchen Service may.");
        }
    }

    public boolean kitchenStaffMaySetPreparationStatuses() {
        return kitchenStaffMaySetPreparationStatuses;
    }
}
