package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.TableSessionResponse;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TableSessionOpeningService {
    private final QrCodeValidationService validation;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final SessionOrderProtection protection;
    private final TransactionTemplate writes;
    private final TransactionTemplate mandatory;

    public TableSessionOpeningService(QrCodeValidationService validation, DiningTableRepository tables,
                                      TableSessionRepository sessions, SessionOrderProtection protection,
                                      PlatformTransactionManager manager) {
        this.validation = validation; this.tables = tables; this.sessions = sessions; this.protection = protection;
        writes = new TransactionTemplate(manager);
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Transactional(propagation = Propagation.NEVER)
    public OrderSessionState prepare(String token) {
        return readState(token);
    }

    private OrderSessionState readState(String token) {
        // Short local QR validation finishes before any future Order HTTP query.
        var context = validation.validate(token);
        var live = sessions.findByRestaurantIdAndTableIdAndStatusIn(context.restaurantId(), context.tableId(),
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        return live.map(main -> protection.inspect(context.restaurantId(), main.getId())).orElse(null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public OpeningResult open(String token) {
        var state = readState(token);
        return writes.execute(status -> openLocked(token, state));
    }

    public OpeningResult openLocked(String token, OrderSessionState state) {
        return mandatory.execute(status -> openInTransaction(token, state));
    }

    private OpeningResult openInTransaction(String token, OrderSessionState state) {
        var context = validation.validate(token);
        var table = tables.findForQrManagement(context.restaurantId(), context.tableId()).orElseThrow(InvalidQrCodeException::new);
        var live = sessions.findByRestaurantIdAndTableIdAndStatusIn(context.restaurantId(), context.tableId(),
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        if (live.isPresent()) {
            var session = live.orElseThrow();
            protection.cancellationDeadline(state, session, null, context.checkedAt());
            boolean held = protection.protectsMain(state, session, context.checkedAt());
            if (!session.isOpenAt(context.checkedAt(), held) || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED) {
                throw new TableSessionConflictException();
            }
            session.recordActivityAt(context.checkedAt(), held);
            sessions.flush();
            return new OpeningResult(metadata(session, held), false, context.qrId(), IdleWindow.storedTime(context.checkedAt()));
        }
        table.occupy();
        var session = sessions.saveAndFlush(new TableSession(context.restaurantId(), context.tableId(), context.checkedAt()));
        return new OpeningResult(metadata(session, false), true, context.qrId(), session.getOpenedAt());
    }

    private static TableSessionResponse metadata(TableSession session, boolean held) {
        return new TableSessionResponse(session.getId(), session.getRestaurantId(), session.getTableId(), session.getStatus(),
                session.getVersion(), session.getOpenedAt(), session.getLastActivityAt(), session.getIdleExpiresAt(), held);
    }

    public record OpeningResult(TableSessionResponse session, boolean created, UUID qrCodeId, Instant checkedAt) { }
}
