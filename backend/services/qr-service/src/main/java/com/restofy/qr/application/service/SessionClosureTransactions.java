package com.restofy.qr.application.service;

import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import com.restofy.qr.infrastructure.security.SessionLifecycleAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff authority is retained only for the separate cleaned action. */
@Service
public class SessionClosureTransactions {
    private final GuestSessionContextReader contexts;
    private final TableSessionRepository sessions;
    private final SessionLifecycleAccess access;
    public SessionClosureTransactions(GuestSessionContextReader contexts, TableSessionRepository sessions, SessionLifecycleAccess access) {
        this.contexts = contexts; this.sessions = sessions; this.access = access;
    }

    @Transactional
    public Result cleaned(UUID sessionId, long expectedTableVersion) {
        if (expectedTableVersion < 0) { throw new IllegalArgumentException("Invalid version."); }
        var staff = access.requireStaff();
        var parent = contexts.lockSession(staff.restaurantId(), sessionId);
        var main = parent.main();
        if (main.getStatus() != TableSessionStatus.CLOSED || main.getClosedAt().isAfter(parent.checkedAt())
                || sessions.hasOtherSessionOpenedSince(staff.restaurantId(), main.getTableId(), main.getId(), main.getClosedAt())) {
            throw new SessionClosureConflictException();
        }
        if (parent.table().getOperationalStatus() != TableOperationalStatus.AVAILABLE || !parent.table().isActive()) {
            parent.table().requireVersion(expectedTableVersion); parent.table().finishCleaning(); sessions.flush();
        }
        return new Result(main.getId(), main.getTableId(), main.getStatus(), main.getVersion(), main.getClosingAt(),
                main.getClosedAt(), main.getClosedByUserId(), parent.table().getOperationalStatus(), parent.table().getVersion());
    }
}
