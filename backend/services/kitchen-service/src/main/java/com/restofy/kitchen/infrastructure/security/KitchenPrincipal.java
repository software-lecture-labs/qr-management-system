package com.restofy.kitchen.infrastructure.security;

import java.util.UUID;

/** Verified identity of the caller. The tenant comes from here, never from request parameters or headers. */
public record KitchenPrincipal(UUID userId, UUID restaurantId) {
}
