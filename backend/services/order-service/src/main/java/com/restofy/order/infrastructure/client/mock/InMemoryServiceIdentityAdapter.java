package com.restofy.order.infrastructure.client.mock;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.enums.ServiceName;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOCK of the service identity verifier, active ONLY in the {@code dev} and {@code test} profiles. It performs no real
 * authentication: it maps three fixed placeholder credentials to the Kitchen, Payment and QR service identities so the
 * internal endpoints can be exercised. Any other profile gets
 * {@link com.restofy.order.infrastructure.client.UnavailableServiceIdentityAdapter}.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class InMemoryServiceIdentityAdapter implements ServiceIdentityPort {

    public static final String KITCHEN_CREDENTIAL = "dev-mock-service-kitchen";
    public static final String PAYMENT_CREDENTIAL = "dev-mock-service-payment";
    public static final String QR_CREDENTIAL = "dev-mock-service-qr";

    private final Map<String, ServiceActor> services = new ConcurrentHashMap<>();

    @PostConstruct
    void announce() {
        reset();
        log.warn("MOCK service identity adapter is active (dev/test profile only). No real service authentication happens.");
    }

    @Override
    public Optional<ServiceActor> resolveService(String credential) {
        return Optional.ofNullable(credential).map(services::get);
    }

    /** Restores the default fixtures. */
    public final void reset() {
        services.clear();
        services.put(KITCHEN_CREDENTIAL, new ServiceActor(ServiceName.KITCHEN));
        services.put(PAYMENT_CREDENTIAL, new ServiceActor(ServiceName.PAYMENT));
        services.put(QR_CREDENTIAL, new ServiceActor(ServiceName.QR));
    }
}
