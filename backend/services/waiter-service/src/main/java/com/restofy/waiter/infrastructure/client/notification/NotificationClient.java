package com.restofy.waiter.infrastructure.client.notification;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Adapter for the Notification service, the owner of notification delivery and per-staff read state. The
 * contract is a DRAFT; Notification has not implemented it yet.
 *
 * <p>A request is never repeated here. Repeating is the job of the persistent delivery queue, which resends
 * the same stored body with the same event id, so a retry is always the same operation.
 */
@Component
public class NotificationClient {

    public static final String DEPENDENCY = "notification";
    public static final String EVENTS_PATH = "/api/v1/internal/service-call-events";

    private final RestClient client;

    public NotificationClient(RestClient.Builder builder, ServiceUrlProperties urls) {
        this.client = builder.clone().baseUrl(urls.notificationUrl().toString()).build();
    }

    /** Sends the stored event JSON as is; the event id doubles as the idempotency key. */
    public NotificationAckDto publish(UUID eventId, String eventJson) {
        NotificationAckDto ack = DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri(EVENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", eventId.toString())
                .body(eventJson)
                .retrieve().body(NotificationAckDto.class));
        if (!eventId.equals(ack.eventId())) {
            throw new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                    "notification service returned an unusable response: acknowledgement of another event");
        }
        return ack;
    }
}
