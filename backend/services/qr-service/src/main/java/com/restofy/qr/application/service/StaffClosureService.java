package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.StaffClosureResponse;
import com.restofy.qr.domain.SessionClosure.Staff;
import com.restofy.qr.domain.StaffClosure.*;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import com.restofy.qr.domain.exception.StaffClosureException;
import com.restofy.qr.domain.exception.StaffClosureException.Code;
import com.restofy.qr.infrastructure.security.SessionLifecycleAccess;
import com.restofy.qr.infrastructure.security.WaiterLifecycleAccess;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Staff may request normal closure; only Order can authorise its financial/scope conditions. No override exists here. */
@Service
public class StaffClosureService {
    private static final Logger LOG = LoggerFactory.getLogger(StaffClosureService.class);
    private final SessionLifecycleAccess staffAccess;
    private final WaiterLifecycleAccess waiterAccess;
    private final SessionOrderProtection protection;
    private final SessionTransitionTransactions transitions;
    private final TableSessionClosureService lifecycle;
    private final StaffClosureViews views;

    public StaffClosureService(SessionLifecycleAccess staffAccess, WaiterLifecycleAccess waiterAccess,
                                SessionOrderProtection protection, SessionTransitionTransactions transitions,
                                TableSessionClosureService lifecycle, StaffClosureViews views) {
        this.staffAccess = staffAccess; this.waiterAccess = waiterAccess; this.protection = protection;
        this.transitions = transitions; this.lifecycle = lifecycle; this.views = views;
    }

    @Transactional(propagation = Propagation.NEVER)
    public StaffClosureResponse close(Request request, Origin origin) {
        Staff actor = actor(origin);
        if (!actor.restaurantId().equals(request.restaurantId()) || !actor.userId().equals(request.requestedByUserId())) {
            throw new AccessDeniedException("Staff closure context does not match.");
        }
        var known = transitions.findCommand(actor.restaurantId(), request.commandId());
        if (known.isPresent()) {
            SessionTransitionTransactions.requireSameStaffIntent(known.orElseThrow(), request, origin);
        } else {
            var state = protection.inspect(actor.restaurantId(), request.tableSessionId());
            transitions.beginStaff(request, origin, state);
        }
        try { lifecycle.resume(actor.restaurantId(), request.commandId()); }
        catch (SessionClosureUnavailableException _) {
            // The durable request or local receipt exists. Tell the caller what is known, not a fabricated failure.
            LOG.warn("event=QR_STAFF_CLOSURE_PENDING");
        }
        return views.response(actor.restaurantId(), request.tableId(), request.tableSessionId(), request.commandId(), true);
    }

    @Transactional(propagation = Propagation.NEVER)
    public StaffClosureResponse status(UUID restaurantId, UUID tableId, UUID sessionId, UUID commandId, Origin origin) {
        Staff actor = actor(origin);
        if (!actor.restaurantId().equals(restaurantId)) { throw new AccessDeniedException("Staff closure context does not match."); }
        var command = transitions.findCommand(actor.restaurantId(), commandId)
                .orElseThrow(() -> new StaffClosureException(Code.COMMAND_NOT_FOUND));
        if (command.initiatedBy() == null || command.initiatedBy().origin() != origin
                || !command.snapshot().tableId().equals(tableId) || !command.snapshot().sessionId().equals(sessionId)) {
            throw new StaffClosureException(Code.COMMAND_NOT_FOUND);
        }
        return views.response(actor.restaurantId(), tableId, sessionId, commandId, false);
    }

    private Staff actor(Origin origin) {
        return origin == Origin.WAITER_SERVICE ? waiterAccess.requireStaff() : staffAccess.requireStaff();
    }
}
