package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.StaffClosureResponse;
import com.restofy.qr.api.dto.response.StaffClosureResponse.*;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.StaffClosureException;
import com.restofy.qr.domain.exception.StaffClosureException.Code;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only receipt/status, never calls Order or resumes an operation from GET. */
@Service
public class StaffClosureViews {
    private final SessionTransitionTransactions transitions;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;

    public StaffClosureViews(SessionTransitionTransactions transitions, DiningTableRepository tables,
                              TableSessionRepository sessions, GuestSessionRepository guests) {
        this.transitions = transitions; this.tables = tables; this.sessions = sessions; this.guests = guests;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StaffClosureResponse response(UUID restaurantId, UUID tableId, UUID sessionId, UUID commandId, boolean originalReceipt) {
        var view = transitions.view(restaurantId, commandId);
        var table = tables.findByRestaurantIdAndId(restaurantId, tableId)
                .orElseThrow(() -> new StaffClosureException(Code.SESSION_NOT_FOUND));
        var result = view.result();
        State state = state(view);
        if (state != State.CLOSED) {
            String rejection = state == State.REJECTED ? Code.CLOSURE_BLOCKED.name() : null;
            return new StaffClosureResponse(commandId, state, tableId, sessionId, null, null, currentTable(table), rejection, List.of());
        }
        int total = Math.toIntExact(guests.countByRestaurantIdAndTableSessionId(restaurantId, sessionId));
        long active = guests.countByRestaurantIdAndTableSessionIdAndStatus(restaurantId, sessionId, GuestSessionStatus.ACTIVE);
        if (active != 0 || result == null || result.status() != TableSessionStatus.CLOSED) {
            throw new StaffClosureException(Code.CLOSURE_BLOCKED);
        }
        Table responseTable = originalReceipt ? table(table, result.tableStatus(), result.tableVersion(), null) : currentTable(table);
        return new StaffClosureResponse(commandId, State.CLOSED, tableId, sessionId, result.completedAt(),
                new GuestSessions(total, total), responseTable, null,
                view.confirmation() != null && view.confirmation().scopeDrift() ? List.of("RECONCILIATION_REQUIRED") : List.of());
    }

    private static State state(OperationView view) {
        return switch (view.stage()) {
            case APPLIED, COMPLETED -> State.CLOSED;
            case REQUESTED, ABORTING -> State.CLOSING;
            case ABORTED -> State.REJECTED;
        };
    }

    private Table currentTable(DiningTable table) {
        var current = sessions.findByRestaurantIdAndTableIdAndStatusIn(table.getRestaurantId(), table.getId(),
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        LiveSession live = current.map(main -> new LiveSession(main.getId(), main.getStatus(), main.getOpenedAt(),
                main.getLastActivityAt(), null, main.getVersion())).orElse(null);
        return table(table, table.getOperationalStatus(), table.getVersion(), live);
    }

    private static Table table(DiningTable table, TableOperationalStatus status, long version, LiveSession live) {
        return new Table(table.getId(), table.getRestaurantId(), table.getTableNumber(), table.getDisplayName(),
                table.getCapacity(), status, table.isActive(), version, live);
    }
}
