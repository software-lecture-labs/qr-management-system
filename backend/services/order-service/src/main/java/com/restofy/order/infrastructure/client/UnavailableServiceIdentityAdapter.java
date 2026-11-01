package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed stand-in used outside the dev/test profiles until a real service identity mechanism exists. It never
 * authenticates anybody, so the internal endpoints cannot be opened by a forged service credential in production.
 */
@Component
@Profile("!dev & !test")
public class UnavailableServiceIdentityAdapter implements ServiceIdentityPort {

    @Override
    public Optional<ServiceActor> resolveService(String credential) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Service authentication is currently unavailable");
    }
}
