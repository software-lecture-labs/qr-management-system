package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.PaymentSummaryStatus;

import java.time.Instant;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;

/** DRAFT Order contract v0: derived payment summary. Payment is the authority; this is a display projection. */
public record PaymentProjectionDto(PaymentSummaryStatus status, long sourceVersion, Instant asOf) {

    public PaymentProjectionDto {
        required(status, "payment.status");
        required(asOf, "payment.asOf");
        nonNegative(sourceVersion, "payment.sourceVersion");
    }
}
