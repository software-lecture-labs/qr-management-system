package com.restofy.waiter.infrastructure.client.notification;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT Notification contract v0: the acknowledgement of one call event. Every outcome means "Notification
 * has this or a newer state of the call", so all three finish the delivery on Waiter's side.
 */
public record NotificationAckDto(UUID eventId, Outcome outcome) {

    public enum Outcome {
        /** The event changed Notification's state. */
        APPLIED,
        /** The same event id was processed before (a retry after a lost answer). */
        DUPLICATE,
        /** Notification already knows a newer version of the call; this event changed nothing. */
        STALE
    }

    public NotificationAckDto {
        required(eventId, "eventId");
        required(outcome, "outcome");
    }
}
