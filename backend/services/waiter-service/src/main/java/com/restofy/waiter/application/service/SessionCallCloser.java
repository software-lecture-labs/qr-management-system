package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.ActorType;
import com.restofy.waiter.domain.enums.CallEventType;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Ends the unresolved calls of one main table session once QR has confirmed that the session is closed. Only
 * the calls of that restaurant and that session are touched, so a call of the table's next session is out of
 * reach by construction. A call is cancelled, never deleted and never marked resolved (nobody did the work);
 * its history stays, and the end is queued for Notification through the same durable path as every other call
 * change. Runs inside the caller's transaction, which also records the command's completion.
 */
@Component
class SessionCallCloser {

    private final ServiceRequestRepository calls;
    private final CallEvents events;

    SessionCallCloser(ServiceRequestRepository calls, CallEvents events) {
        this.calls = calls;
        this.events = events;
    }

    /** @return how many calls were ended */
    int closeCallsOf(TableCommand closeCommand, Instant now) {
        List<ServiceRequest> unresolved = calls.findByRestaurantIdAndTableSessionIdAndStatusInOrderByCreatedAtAscIdAsc(
                closeCommand.getRestaurantId(), closeCommand.getTableSessionId(), ServiceRequestStatus.UNRESOLVED);
        for (ServiceRequest call : unresolved) {
            ServiceRequestStatus from = call.getStatus();
            call.cancelForTableClosure(closeCommand.getId(), now);
            calls.saveAndFlush(call);
            events.record(call, CallEventType.CANCELLED, from, ActorType.STAFF, closeCommand.getRequestedByUserId(), now);
        }
        return unresolved.size();
    }
}
