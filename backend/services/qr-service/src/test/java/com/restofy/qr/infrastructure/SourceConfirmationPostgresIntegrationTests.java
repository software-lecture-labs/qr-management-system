package com.restofy.qr.infrastructure;

import com.restofy.qr.application.service.StaffClosureService;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.StaffClosure.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** K13 with real QR/Flyway/PostgreSQL and controlled Order receipts; no real network or Payment proof. */
class SourceConfirmationPostgresIntegrationTests extends TransitionIntegrationSupport {
    @Autowired StaffClosureService staffClosure;

    @Test void driftCompletesWithoutReopeningAndItsReceiptIsDurableTenantScopedAndImmutable() {
        var f = create();
        doAnswer(invocation -> {
            Command c = invocation.getArgument(0); LocalResult r = invocation.getArgument(1); outsideTransaction();
            return confirmation(c, SourceState.COMPLETED, r.effect(), true, List.of("RECONCILIATION_REQUIRED"));
        }).when(source).acknowledge(any(), any());
        var closed = settle(f);
        assertThat(closed.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(closed.confirmation().scopeDrift()).isTrue();
        assertThat(closed.confirmation().driftBlockers()).containsExactly("RECONCILIATION_REQUIRED");
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(lifecycle.resume(tenant, closed.operationId())).isEqualTo(closed);
        verify(source, times(1)).acknowledge(any(), any()); verify(source, never()).abort(any());
        assertThat(count(closed.operationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from session_source_confirmations where restaurant_id=?", Integer.class,
                UUID.randomUUID())).isZero();
        var id = closed.operationId();
        assertThatThrownBy(() -> jdbc.update("update session_source_confirmations set confirmation_json='{}' where operation_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from session_source_confirmations where operation_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void staffReceiptRemainsClosedAndCarriesAFixedReconciliationWarning() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        var request = new Request(UUID.randomUUID(), tenant, f.code().tableId(), main(f),
                sessions.findById(main(f)).orElseThrow().getVersion(), staffId, null, time.get());
        doAnswer(invocation -> {
            Command c = invocation.getArgument(0); LocalResult r = invocation.getArgument(1); outsideTransaction();
            return confirmation(c, SourceState.COMPLETED, r.effect(), true, List.of("PAYMENT_NOT_SETTLED"));
        }).when(source).acknowledge(any(), any());
        var response = staffClosure.close(request, Origin.STAFF);
        assertThat(response.state().name()).isEqualTo("CLOSED");
        assertThat(response.blockers()).containsExactly("RECONCILIATION_REQUIRED");
        assertThat(response.table().operationalStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        assertThat(staffClosure.status(tenant, request.tableId(), request.tableSessionId(), request.commandId(), Origin.STAFF))
                .isEqualTo(response);
        assertThat(transitions.view(tenant, request.commandId()).confirmation().driftBlockers()).containsExactly("PAYMENT_NOT_SETTLED");
    }

    @ParameterizedTest @ValueSource(strings = {"operation", "tenant", "session", "digest", "purpose", "effect", "state", "revision", "incomplete", "future", "beforeReceipt"})
    void anUnverifiedAckNeverCompletesOrReopensTheAppliedLocalResult(String kind) {
        var f = create();
        doAnswer(invocation -> {
            outsideTransaction(); Command c = invocation.getArgument(0); LocalResult r = invocation.getArgument(1);
            return new Confirmation(kind.equals("operation") ? UUID.randomUUID() : c.operationId(),
                    kind.equals("tenant") ? UUID.randomUUID() : tenant,
                    kind.equals("session") ? UUID.randomUUID() : main(f), kind.equals("purpose") ? Purpose.IDLE : c.purpose(),
                    kind.equals("digest") ? "f".repeat(64) : c.digest(),
                    kind.equals("state") ? SourceState.PREPARED : SourceState.COMPLETED,
                    kind.equals("effect") ? Effect.IDLE_MAIN : r.effect(), kind.equals("revision") ? 0 : 1,
                    kind.equals("future") ? time.get().plusSeconds(1) : kind.equals("beforeReceipt") ? time.get().minusSeconds(1) : time.get(),
                    !kind.equals("incomplete"), false, List.of());
        }).when(source).acknowledge(any(), any());
        var closed = settle(f);
        assertThat(closed.stage()).isEqualTo(Stage.APPLIED);
        assertThat(closed.confirmation()).isNull(); assertThat(count(closed.operationId())).isZero();
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        verify(source, never()).abort(any());
        doAnswer(invocation -> confirmed(invocation.getArgument(0), invocation.getArgument(1))).when(source).acknowledge(any(), any());
        assertThat(lifecycle.resume(tenant, closed.operationId()).stage()).isEqualTo(Stage.COMPLETED);
        assertThat(count(closed.operationId())).isEqualTo(1);
    }

    @Test void aCompleteCurrentUnknownWithdrawalUsesAbortAndNotStartedIsADefinitiveNoHold() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doAnswer(invocation -> authority(invocation.getArgument(0), SourceStatus.UNKNOWN, Effect.NONE, Set.of())).when(source).prepare(any());
        doAnswer(invocation -> confirmation(invocation.getArgument(0), SourceState.NOT_STARTED, Effect.NONE, false, List.of()))
                .when(source).abort(any());
        var result = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(result.stage()).isEqualTo(Stage.ABORTED);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(result.result()).isNull(); assertThat(count(result.operationId())).isZero();
    }

    @Test void abortWithAnotherDigestCannotReleaseTheClosingSession() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doAnswer(invocation -> authority(invocation.getArgument(0), SourceStatus.BLOCKED, Effect.NONE, Set.of())).when(source).prepare(any());
        doAnswer(invocation -> {
            outsideTransaction(); Command c = invocation.getArgument(0);
            return new Confirmation(c.operationId(), tenant, main(f), c.purpose(), "f".repeat(64), SourceState.RELEASED,
                    Effect.NONE, 1, time.get(), true, false, List.of());
        }).when(source).abort(any());
        var pending = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(pending.stage()).isEqualTo(Stage.ABORTING);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
        doAnswer(invocation -> released(invocation.getArgument(0))).when(source).abort(any());
        assertThat(lifecycle.resume(tenant, pending.operationId()).stage()).isEqualTo(Stage.ABORTED);
    }

    @Test void expiredPermitIsRefreshedOnceWithTheSameOperationBeforeApplying() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doAnswer(invocation -> {
            outsideTransaction(); Command c = invocation.getArgument(0);
            return new Authority(c, SourceStatus.AUTHORISED, Effect.SETTLED_CLOSE, 1, time.get().minusSeconds(60),
                    time.get(), true, true, Set.of());
        }).when(source).prepare(any());
        var result = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(result.stage()).isEqualTo(Stage.COMPLETED);
        verify(source, times(1)).status(any()); verify(source, times(1)).prepare(any());
        verify(source, never()).abort(any());
    }

    @Test void expiryWhileWaitingForTheQrLockDoesNotApplyOrAbortAStalePermission() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        var state = source.inspect(tenant, main(f));
        var id = transitions.begin(tenant, main(f), Purpose.SETTLED, state);
        var command = transitions.findCommand(tenant, id).orElseThrow();
        var permit = new Authority(command, SourceStatus.AUTHORISED, Effect.SETTLED_CLOSE, 1,
                time.get(), time.get().plusSeconds(60), true, true, Set.of());
        time.set(time.get().plusSeconds(60));
        assertThat(transitions.apply(tenant, id, permit)).isEqualTo(ApplyOutcome.REFRESH_REQUIRED);
        assertThat(transitions.view(tenant, id).stage()).isEqualTo(Stage.REQUESTED);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        verify(source, never()).abort(any());
        assertThat(lifecycle.resume(tenant, id).stage()).isEqualTo(Stage.COMPLETED);
    }

    @Test void lostAckThenDriftUsesTheOriginalLocalReceiptAndNeverStartsANewOperation() {
        var f = create(); doThrow(new SessionClosureUnavailableException()).when(source).acknowledge(any(), any());
        assertThatThrownBy(() -> settle(f)).isInstanceOf(SessionClosureUnavailableException.class);
        var row = operations.findByRestaurantIdAndSessionIdAndStageIn(tenant, main(f), List.of(Stage.APPLIED)).orElseThrow();
        String receipt = row.getResultJson();
        doAnswer(invocation -> {
            outsideTransaction(); Command c = invocation.getArgument(0); LocalResult r = invocation.getArgument(1);
            return confirmation(c, SourceState.COMPLETED, r.effect(), true, List.of("RECONCILIATION_REQUIRED"));
        }).when(source).acknowledge(any(), any());
        assertThat(lifecycle.resume(tenant, row.getId()).stage()).isEqualTo(Stage.COMPLETED);
        assertThat(operations.findById(row.getId()).orElseThrow().getResultJson()).isEqualTo(receipt);
        verify(source, never()).status(any()); verify(source, never()).abort(any());
    }

    private int count(UUID operationId) {
        return jdbc.queryForObject("select count(*) from session_source_confirmations where operation_id=?", Integer.class, operationId);
    }
}
