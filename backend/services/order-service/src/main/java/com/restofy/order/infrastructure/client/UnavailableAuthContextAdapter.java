package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed stand-in used outside the dev/test profiles until the real Auth Service adapter exists. It never
 * authenticates anybody, so no staff identity can be forged in production.
 */
@Component
@Profile("!dev & !test")
public class UnavailableAuthContextAdapter implements AuthContextPort {

    @Override
    public Optional<StaffActor> resolveStaff(String credential) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable");
    }
}
