package com.restofy.order.infrastructure.client.mock;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOCK of the Auth Service, active ONLY in the {@code dev} and {@code test} profiles. It performs no real
 * authentication: it maps a few fixed placeholder credentials to fixed staff identities so role and restaurant
 * isolation can be exercised. Any other profile gets {@link com.restofy.order.infrastructure.client.UnavailableAuthContextAdapter}.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class InMemoryAuthContextAdapter implements AuthContextPort {

    private final Map<String, StaffActor> staff = new ConcurrentHashMap<>();

    @PostConstruct
    void announce() {
        reset();
        log.warn("MOCK Auth context adapter is active (dev/test profile only). No real staff authentication happens.");
    }

    @Override
    public Optional<StaffActor> resolveStaff(String credential) {
        return Optional.ofNullable(credential).map(staff::get);
    }

    public void register(String credential, StaffActor actor) {
        staff.put(credential, actor);
    }

    public void unregister(String credential) {
        staff.remove(credential);
    }

    /** Restores the default fixtures. */
    public final void reset() {
        staff.clear();
        staff.put(DevFixtures.STAFF_ADMIN_A, new StaffActor(DevFixtures.USER_ADMIN_A, DevFixtures.RESTAURANT_A, StaffRole.ADMIN));
        staff.put(DevFixtures.STAFF_WAITER_A, new StaffActor(DevFixtures.USER_WAITER_A, DevFixtures.RESTAURANT_A, StaffRole.WAITER));
        staff.put(DevFixtures.STAFF_KITCHEN_A, new StaffActor(DevFixtures.USER_KITCHEN_A, DevFixtures.RESTAURANT_A, StaffRole.KITCHEN));
        staff.put(DevFixtures.STAFF_ADMIN_B, new StaffActor(DevFixtures.USER_ADMIN_B, DevFixtures.RESTAURANT_B, StaffRole.ADMIN));
        staff.put(DevFixtures.STAFF_WAITER_B, new StaffActor(DevFixtures.USER_WAITER_B, DevFixtures.RESTAURANT_B, StaffRole.WAITER));
    }
}
