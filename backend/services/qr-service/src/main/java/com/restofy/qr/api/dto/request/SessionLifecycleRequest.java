package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Close expects the main version; cleaned expects the table version. */
public record SessionLifecycleRequest(@NotNull @PositiveOrZero Long version) { }
