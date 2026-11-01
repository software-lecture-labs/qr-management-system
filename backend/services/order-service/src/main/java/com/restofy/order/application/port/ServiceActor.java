package com.restofy.order.application.port;

import com.restofy.order.domain.enums.ServiceName;

import java.util.Objects;

/**
 * A calling service as verified through {@link ServiceIdentityPort}. A service is never a user: it has no
 * restaurant of its own, so every event it sends is checked against the order it names.
 */
public record ServiceActor(ServiceName name) {

    public ServiceActor {
        Objects.requireNonNull(name, "name");
    }
}
