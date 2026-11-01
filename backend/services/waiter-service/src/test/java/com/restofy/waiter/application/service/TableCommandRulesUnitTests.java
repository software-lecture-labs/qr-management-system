package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.request.CloseTableRequest;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.CallClosureReason;
import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.InvalidRequestException;
import com.restofy.waiter.infrastructure.client.qr.QrCleanResultDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrCloseResultDto;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import com.restofy.waiter.infrastructure.config.TableCommandReconcilerProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.waiter.domain.enums.TableSessionStatus.OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** State rules of the table command record, the call closure and the QR answers that Waiter refuses to believe. */
class TableCommandRulesUnitTests {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static TableCommand.Origin origin() {
        return new TableCommand.Origin(UUID.randomUUID(), RESTAURANT, TABLE, 3, STAFF, "key-0123456789", "digest", NOW);
    }

    private static QrTableDto table(TableOperationalStatus status, UUID liveSession) {
        QrSessionDto session = liveSession == null ? null : new QrSessionDto(liveSession, OPEN, NOW, NOW, 2, 3L);
        return new QrTableDto(TABLE, RESTAURANT, "12", null, 4, status, true, 8L, session);
    }

    // ----- the command record -----

    @Test
    void aNewCommandIsPendingAndOpen() {
        TableCommand close = TableCommand.close(origin(), SESSION, "Müşteri ayrıldı");
        assertEquals(TableCommandStatus.PENDING, close.getStatus());
        assertEquals(TableCommandType.CLOSE, close.getType());
        assertEquals(SESSION, close.getTableSessionId());
        assertEquals("Müşteri ayrıldı", close.getReasonText());
        assertTrue(close.isOpen());
        TableCommand clean = TableCommand.clean(origin());
        assertEquals(TableCommandType.CLEAN, clean.getType());
        assertNull(clean.getTableSessionId());
    }

    @Test
    void anAnswerOfQrIsFinalAndCannotBeRevised() {
        TableCommand completed = TableCommand.close(origin(), SESSION, null);
        completed.completeClose(NOW, 2, 3, true, NOW);
        assertEquals(TableCommandStatus.COMPLETED, completed.getStatus());
        assertEquals(2, completed.getEndedGuestSessions());
        assertEquals(3, completed.getCancelledCallCount());
        assertTrue(completed.isReconciliationRequired(), "QR's K13 warning is kept with the definite closure");
        assertFalse(completed.isOpen());
        assertThrows(IllegalStateException.class, () -> completed.reject("SESSION_NOT_OPEN", null, NOW));
        assertThrows(IllegalStateException.class, () -> completed.markUnknown(NOW));

        TableCommand rejected = TableCommand.close(origin(), SESSION, null);
        rejected.reject("CLOSURE_BLOCKED", "UNPAID_BALANCE", NOW);
        assertEquals("UNPAID_BALANCE", rejected.getRejectionDetail());
        assertThrows(IllegalStateException.class, () -> rejected.completeClose(NOW, 1, 0, false, NOW));
        assertFalse(rejected.isReconciliationRequired());
    }

    @Test
    void unknownAndInProgressStayOpenAndCanMoveOnToAFinalAnswer() {
        TableCommand command = TableCommand.close(origin(), SESSION, null);
        command.markUnknown(NOW);
        assertTrue(command.isOpen());
        command.markInProgress(NOW);
        assertEquals(TableCommandStatus.IN_PROGRESS, command.getStatus());
        assertTrue(command.isOpen(), "CLOSING is not a success");
        command.markInFlight(NOW);
        assertEquals(TableCommandStatus.PENDING, command.getStatus());
        command.completeClose(NOW, 0, 0, false, NOW);
        assertEquals(TableCommandStatus.COMPLETED, command.getStatus());
        assertFalse(command.isReconciliationRequired());
    }

    @Test
    void aCleaningRemembersWhetherQrShowedTheTableReady() {
        TableCommand ready = TableCommand.clean(origin());
        ready.completeClean(9, true, NOW);
        assertEquals(9L, ready.getTableVersionAfter());
        assertTrue(ready.getReadyForNewCustomer());
        TableCommand reused = TableCommand.clean(origin());
        reused.completeClean(11, false, NOW);
        assertFalse(reused.getReadyForNewCustomer(), "A table that is in use again is not ready, even if it was cleaned");
    }

    // ----- calls that end with a closed session -----

    @Test
    void aCallThatEndsWithItsTableSessionIsCancelledNotResolved() {
        ServiceRequest call = ServiceRequest.open(UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID(),
                ServiceRequestType.BILL, null, NOW);
        call.claim(STAFF, NOW);
        UUID command = UUID.randomUUID();
        call.cancelForTableClosure(command, NOW);
        assertEquals(ServiceRequestStatus.CANCELLED, call.getStatus());
        assertEquals(CallClosureReason.TABLE_SESSION_CLOSED, call.getClosureReason());
        assertEquals(command, call.getCancelledByCommandId());
        assertNull(call.getResolvedByUserId(), "Nobody did the work, so nobody is the resolver");
        assertNull(call.getResolvedAt());
        assertEquals(STAFF, call.getClaimedByUserId(), "The history of the call is kept");
    }

    @Test
    void aFinishedCallCannotBeCancelledAgain() {
        ServiceRequest resolved = ServiceRequest.open(UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID(),
                ServiceRequestType.HELP, null, NOW);
        resolved.resolve(STAFF, NOW);
        assertEquals("CALL_ALREADY_RESOLVED",
                assertThrows(CallConflictException.class, () -> resolved.cancelForTableClosure(UUID.randomUUID(), NOW)).code());
        ServiceRequest cancelled = ServiceRequest.open(UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID(),
                ServiceRequestType.HELP, null, NOW);
        cancelled.cancelForTableClosure(UUID.randomUUID(), NOW);
        assertEquals("INVALID_TRANSITION",
                assertThrows(CallConflictException.class, () -> cancelled.cancelForTableClosure(UUID.randomUUID(), NOW)).code());
        assertEquals("INVALID_TRANSITION",
                assertThrows(CallConflictException.class, () -> cancelled.resolve(STAFF, NOW)).code());
    }

    // ----- QR answers that must not be believed -----

    @Test
    void aCloseIsNotClosedWhileAnyGuestSessionIsLeft() {
        UUID commandId = UUID.randomUUID();
        var partial = new QrCloseResultDto.GuestSessions(3, 2);
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(commandId, QrCloseResultDto.State.CLOSED,
                TABLE, SESSION, NOW, partial, table(TableOperationalStatus.CLEANING, null), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto.GuestSessions(1, 2));
        QrCloseResultDto all = new QrCloseResultDto(commandId, QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW,
                new QrCloseResultDto.GuestSessions(3, 3), table(TableOperationalStatus.CLEANING, null), null, List.of());
        assertEquals(3, all.guestSessions().ended());
    }

    @Test
    void aClosedSessionCannotStillBeTheLiveSessionOfItsTable() {
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(),
                QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW, new QrCloseResultDto.GuestSessions(1, 1),
                table(TableOperationalStatus.OCCUPIED, SESSION), null, List.of()));
        // A different, newer session of the same table is fine: the old one is simply closed.
        new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW,
                new QrCloseResultDto.GuestSessions(1, 1), table(TableOperationalStatus.OCCUPIED, UUID.randomUUID()), null, List.of());
    }

    @Test
    void aClosedAnswerMayOnlyCarryTheReconciliationWarningAndStaysClosed() {
        var guests = new QrCloseResultDto.GuestSessions(2, 2);
        var cleaning = table(TableOperationalStatus.CLEANING, null);
        QrCloseResultDto drift = new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSED, TABLE, SESSION,
                NOW, guests, cleaning, null, List.of(QrCloseResultDto.RECONCILIATION_REQUIRED));
        assertEquals(QrCloseResultDto.State.CLOSED, drift.state());
        assertTrue(drift.reconciliationRequired());
        assertFalse(new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW, guests,
                cleaning, null, List.of()).reconciliationRequired());
        // A closed answer naming a closure blocker or a rejection contradicts itself.
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(),
                QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW, guests, cleaning, null, List.of("UNPAID_BALANCE")));
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(),
                QrCloseResultDto.State.CLOSED, TABLE, SESSION, NOW, guests, cleaning, "CLOSURE_BLOCKED", List.of()));
        // The warning belongs to a closure only; a refusal never becomes a reconciliation case.
        assertFalse(new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.REJECTED, TABLE, SESSION, null, null,
                null, "CLOSURE_BLOCKED", List.of(QrCloseResultDto.RECONCILIATION_REQUIRED)).reconciliationRequired());
    }

    @Test
    void aClosedAnswerNeedsItsTimeAndItsGuestCounts() {
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSED,
                TABLE, SESSION, null, new QrCloseResultDto.GuestSessions(1, 1), null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSED,
                TABLE, SESSION, NOW, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.REJECTED,
                TABLE, SESSION, null, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QrCloseResultDto(UUID.randomUUID(), QrCloseResultDto.State.CLOSING,
                TABLE, SESSION, null, null, null, "SESSION_NOT_OPEN", List.of()));
    }

    @Test
    void aCleaningAnswerReportsReadinessOnlyForAnAvailableTableWithoutALiveSession() {
        UUID id = UUID.randomUUID();
        assertTrue(new QrCleanResultDto(id, QrCleanResultDto.Status.APPLIED, null,
                table(TableOperationalStatus.AVAILABLE, null)).tableReady());
        assertFalse(new QrCleanResultDto(id, QrCleanResultDto.Status.APPLIED, null,
                table(TableOperationalStatus.OCCUPIED, UUID.randomUUID())).tableReady());
        assertFalse(new QrCleanResultDto(id, QrCleanResultDto.Status.APPLIED, null,
                table(TableOperationalStatus.CLEANING, null)).tableReady());
        assertThrows(IllegalArgumentException.class, () -> new QrCleanResultDto(id, QrCleanResultDto.Status.APPLIED, null, null));
        assertThrows(IllegalArgumentException.class, () -> new QrCleanResultDto(id, QrCleanResultDto.Status.REJECTED, null, null));
    }

    @Test
    void qrTablesAndSessionsMustQuoteTheirVersions() {
        assertThrows(IllegalArgumentException.class, () -> new QrTableDto(TABLE, RESTAURANT, "1", null, null,
                TableOperationalStatus.AVAILABLE, true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new QrSessionDto(SESSION, OPEN, NOW, NOW, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new QrSessionDto(SESSION, OPEN, NOW, NOW, 1, -1L));
    }

    // ----- text that comes from outside -----

    @Test
    void onlyKnownReasonsAndBlockersFromQrAreKept() {
        assertEquals("CLOSURE_BLOCKED", QrClient.knownCloseRejection("CLOSURE_BLOCKED"));
        assertEquals("QR_REJECTED", QrClient.knownCloseRejection("<script>alert(1)</script>"));
        assertEquals("QR_REJECTED", QrClient.knownCloseRejection(null));
        assertEquals("TABLE_SESSION_ACTIVE", QrClient.knownCleanRejection("TABLE_SESSION_ACTIVE"));
        assertEquals("QR_REJECTED", QrClient.knownCleanRejection("CLOSURE_BLOCKED"), "A close reason is no cleaning reason");
        assertEquals(List.of("UNPAID_BALANCE", "ORDER_ADMISSION_IN_FLIGHT"),
                QrClient.knownBlockers(List.of("UNPAID_BALANCE", "somebody@example.com", "UNPAID_BALANCE", "ORDER_ADMISSION_IN_FLIGHT")));
        assertEquals(Optional.empty(), ClosureBlocker.parse("unpaid_balance"));
    }

    @Test
    void theReasonOfAManualCloseIsShortSingleLineVisibleText() {
        assertEquals("Müşteri ayrıldı", CallMessage.normalize("  Müşteri ayrıldı ", "reason"));
        assertNull(CallMessage.normalize(null, "reason"));
        assertEquals("reason must not be blank",
                assertThrows(InvalidRequestException.class, () -> CallMessage.normalize("​  ", "reason")).getMessage());
        assertEquals("reason must not contain control characters",
                assertThrows(InvalidRequestException.class, () -> CallMessage.normalize("a\nb", "reason")).getMessage());
        assertThrows(InvalidRequestException.class, () -> CallMessage.normalize("x".repeat(301), "reason"));
        assertEquals("x".repeat(300), CallMessage.normalize("x".repeat(300), "reason"));
    }

    @Test
    void theCloseRequestOnlyAcceptsATextReason() {
        assertEquals("ok", new CloseTableRequest(1L, "ok").reasonText());
        assertNull(new CloseTableRequest(1L, null).reasonText());
        assertThrows(InvalidRequestException.class, () -> new CloseTableRequest(1L, 42));
        assertThrows(InvalidRequestException.class, () -> new CloseTableRequest(1L, true));
    }

    // ----- policy of the background check -----

    @Test
    void reconcilerPolicyRejectsNonsense() {
        new TableCommandReconcilerProperties(true, 5000, 20, 20, 48);
        assertThrows(IllegalArgumentException.class, () -> new TableCommandReconcilerProperties(true, 0, 20, 20, 48));
        assertThrows(IllegalArgumentException.class, () -> new TableCommandReconcilerProperties(true, 5000, 0, 20, 48));
        assertThrows(IllegalArgumentException.class, () -> new TableCommandReconcilerProperties(true, 5000, 201, 20, 48));
        assertThrows(IllegalArgumentException.class, () -> new TableCommandReconcilerProperties(true, 5000, 20, 0, 48));
        assertThrows(IllegalArgumentException.class, () -> new TableCommandReconcilerProperties(true, 5000, 20, 20, 0));
        assertEquals(Duration.ofHours(48), new TableCommandReconcilerProperties(true, 5000, 20, 20, 48).maxAge());
    }
}
