package com.restofy.waiter.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * "This table is cleaned", with the table version the staff member saw. QR also refuses the command while the
 * table still has an open or closing main session, whatever the staff member saw.
 */
public record CleanTableRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
