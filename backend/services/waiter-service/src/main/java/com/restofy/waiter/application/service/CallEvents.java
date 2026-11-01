package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ActorType;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.infrastructure.client.notification.ServiceCallEventDto;
import com.restofy.waiter.infrastructure.persistence.repository.CallLedger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the audit event of one business change of a call and queues it for Notification. It is the single
 * place that turns a call change into an event, so a guest's raise, a staff member's claim or resolve and the
 * end of a call with its table session all use the same durable path. Meant to run inside the transaction that
 * changes the call.
 */
@Component
public class CallEvents {

    private final CallLedger ledger;
    private final JsonMapper json;

    public CallEvents(CallLedger ledger, JsonMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    public void record(ServiceRequest call, CallEventType type, ServiceRequestStatus from, ActorType actorType,
                       UUID actorId, Instant now) {
        UUID eventId = UUID.randomUUID();
        String payload = json.writeValueAsString(ServiceCallEventDto.of(eventId, type, now, call));
        ledger.appendEvent(eventId, call.getRestaurantId(), call.getId(), call.getVersion(), type, from,
                call.getStatus(), actorType, actorId, now, payload);
    }
}
