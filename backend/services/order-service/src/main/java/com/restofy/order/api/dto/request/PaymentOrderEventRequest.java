package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment status the Payment Service reports for one order. The identifiers have four DIFFERENT meanings and are never
 * interchangeable:
 * <ul>
 *   <li>{@code eventId}: the unique id of this MESSAGE. Redelivering the same message never changes anything twice.</li>
 *   <li>{@code paymentAttemptId}: one payment ATTEMPT of the guest (required on every event). PENDING starts an attempt,
 *       FAILED / CANCELLED / PAID end it. A new attempt after a failure has a new id. It says nothing about money.</li>
 *   <li>{@code captureId}: one real, completed COLLECTION of money (required on PAID and only there). It is stable and
 *       unique for that collection however many messages or attempts mention it; the same {@code captureId} is the same
 *       money, a different one on the same order is a possible second charge.</li>
 *   <li>{@code occurredAt}: the Payment Service's own clock; it never decides a PAID.</li>
 * </ul>
 * {@code amountMinor} and {@code currency} are required for PENDING and PAID and must equal the order's current due
 * amount. The Order Service never takes an amount, status or reference from a client, only from this verified service.
 */
public record PaymentOrderEventRequest(@NotNull UUID eventId, @NotNull UUID restaurantId, @NotNull UUID orderId,
                                       @NotBlank @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String paymentStatus,
                                       @NotNull UUID paymentAttemptId, UUID captureId,
                                       @PositiveOrZero Long amountMinor,
                                       @Size(min = InputLimits.CURRENCY_CODE_LENGTH,
                                               max = InputLimits.CURRENCY_CODE_LENGTH) String currency,
                                       @NotNull Instant occurredAt) {
}
