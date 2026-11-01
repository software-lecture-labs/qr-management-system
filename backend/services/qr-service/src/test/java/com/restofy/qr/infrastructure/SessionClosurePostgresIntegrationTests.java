package com.restofy.qr.infrastructure;

import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionClosurePostgresIntegrationTests extends TransitionIntegrationSupport {
    @Test void automaticClosureEndsAllGuestsWithoutAStaffApprovalOrSeparateGuestPayments() {
        var f = create(); var guestToken = f.owner().guestToken(); var qrToken = f.code().qrToken(); var spectator = opening.open(qrToken);
        reset(staffAccess);
        var closed = settle(f);
        assertThat(closed.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(closed.result().status()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(closed.result().tableStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        var row = sessions.findById(main(f)).orElseThrow();
        assertThat(row.getClosedByUserId()).isNull();
        assertThat(guests.findById(spectator.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ENDED);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ENDED);
        verifyNoInteractions(staffAccess);
        assertThatThrownBy(() -> validation.validate(guestToken)).isInstanceOf(InvalidGuestSessionException.class);
        assertThatThrownBy(() -> opening.open(qrToken)).isInstanceOf(TableConflictException.class);
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isZero();
        assertThat(lifecycle.resume(tenant, closed.operationId())).isEqualTo(closed);
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "NO_ORDERS", "OPEN_WORK"})
    void aReadHintCannotTurnUnpaidIncompleteOrEmptyWorkIntoSettledClosure(String value) {
        var f = create(); state(f, OrderSessionState.Knowledge.valueOf(value), Set.of());
        assertThat(lifecycle.evaluate(tenant, main(f), Purpose.SETTLED)).isNull();
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        verify(source, never()).prepare(any());
    }

    @Test void aNewOrderBeforeTheSourceGatePreventsClosureAndOnlyVerifiedAbortReopens() {
        var f = create(); var guestToken = f.owner().guestToken(); var qrToken = f.code().qrToken(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doAnswer(invocation -> {
            var command = (Command) invocation.getArgument(0); outsideTransaction();
            assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
            assertThatThrownBy(() -> opening.open(qrToken)).isInstanceOf(TableSessionConflictException.class);
            assertThatThrownBy(() -> validation.validate(guestToken)).isInstanceOf(InvalidGuestSessionException.class);
            return authority(command, SourceStatus.BLOCKED, Effect.NONE, Set.of());
        }).when(source).prepare(any());
        var view = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(view.stage()).isEqualTo(Stage.ABORTED);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        verify(source).abort(any());
    }

    @Test void lostPrepareReplyLeavesOneDurableIntentAndRestartQueriesTheSameCommand() {
        var f = create(); var qrToken = f.code().qrToken(); var sessionId = main(f); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doThrow(new SessionClosureUnavailableException()).when(source).prepare(any());
        assertThatThrownBy(() -> lifecycle.evaluate(tenant, sessionId, Purpose.SETTLED)).isInstanceOf(SessionClosureUnavailableException.class);
        var op = operations.findByRestaurantIdAndSessionIdAndStageIn(tenant, sessionId, List.of(Stage.REQUESTED)).orElseThrow();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
        assertThatThrownBy(() -> opening.open(qrToken)).isInstanceOf(TableSessionConflictException.class);
        var result = lifecycle.resume(tenant, op.getId());
        assertThat(result.stage()).isEqualTo(Stage.COMPLETED);
        verify(source, times(1)).prepare(any()); verify(source, times(1)).status(any());
        assertThat(result.operationId()).isEqualTo(op.getId());
    }

    @Test void anExplicitSourceNotStartedResultReusesTheExactExistingCommand() {
        var f = create(); var sessionId = main(f); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doThrow(new SessionClosureUnavailableException()).when(source).prepare(any());
        assertThatThrownBy(() -> lifecycle.evaluate(tenant, sessionId, Purpose.SETTLED)).isInstanceOf(SessionClosureUnavailableException.class);
        var op = operations.findByRestaurantIdAndSessionIdAndStageIn(tenant, sessionId, List.of(Stage.REQUESTED)).orElseThrow();
        doAnswer(invocation -> authority(invocation.getArgument(0), SourceStatus.NOT_STARTED, Effect.NONE, Set.of())).when(source).status(any());
        doAnswer(invocation -> permit(invocation.getArgument(0))).when(source).prepare(any());
        assertThat(lifecycle.resume(tenant, op.getId()).stage()).isEqualTo(Stage.COMPLETED);
        var captor = org.mockito.ArgumentCaptor.forClass(Command.class);
        verify(source, times(2)).prepare(captor.capture());
        assertThat(captor.getAllValues().getFirst()).isEqualTo(captor.getAllValues().getLast());
    }

    @Test void lostAckReplaysOnlyTheImmutableLocalReceiptAndNeverTouchesANewVisit() {
        var f = create(); doThrow(new SessionClosureUnavailableException()).when(source).acknowledge(any(), any());
        assertThatThrownBy(() -> settle(f)).isInstanceOf(SessionClosureUnavailableException.class);
        var op = operations.findByRestaurantIdAndSessionIdAndStageIn(tenant, main(f), List.of(Stage.APPLIED)).orElseThrow();
        String receipt = op.getResultJson();
        var table = tables.get(f.code().tableId()); lifecycle.cleaned(main(f), table.version());
        time.set(time.get().plusSeconds(1)); var next = opening.open(f.code().qrToken());
        doAnswer(invocation -> confirmed(invocation.getArgument(0), invocation.getArgument(1))).when(source).acknowledge(any(), any());
        var result = lifecycle.resume(tenant, op.getId());
        assertThat(result.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(operations.findById(op.getId()).orElseThrow().getResultJson()).isEqualTo(receipt);
        assertThat(guests.findById(next.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired", "future", "foreign", "incomplete", "unfenced", "unknown", "wrongMode", "foreignGuest"})
    void invalidAuthorityNeverEndsGuestsOrBlindlyReopens(String kind) {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        if (kind.equals("expired")) {
            doAnswer(invocation -> new Authority(invocation.getArgument(0), SourceStatus.AUTHORISED, Effect.SETTLED_CLOSE,
                    1, time.get().minusSeconds(1), time.get(), true, true, Set.of())).when(source).status(any());
        }
        doAnswer(invocation -> {
            Command c = invocation.getArgument(0); outsideTransaction();
            if (kind.equals("null")) { return null; }
            var command = kind.equals("foreign") ? new Command(UUID.randomUUID(), c.purpose(), c.snapshot(), c.requestedAt()) : c;
            return new Authority(command, kind.equals("unknown") ? SourceStatus.UNKNOWN : SourceStatus.AUTHORISED,
                    kind.equals("wrongMode") ? Effect.IDLE_MAIN : Effect.SETTLED_CLOSE, 1,
                    kind.equals("future") ? time.get().plusSeconds(1) : time.get(),
                    kind.equals("expired") ? time.get() : time.get().plusSeconds(30), !kind.equals("incomplete"), !kind.equals("unfenced"),
                    kind.equals("foreignGuest") ? Set.of(UUID.randomUUID()) : Set.of());
        }).when(source).prepare(any());
        var result = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(result.stage()).isEqualTo(Stage.REQUESTED);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        verify(source, never()).acknowledge(any(), any()); verify(source, never()).abort(any());
    }

    @Test void changedLocalVersionRequiresAConfirmedAbortBeforeReopening() {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        doAnswer(invocation -> {
            Command c = invocation.getArgument(0); jdbc.update("update table_sessions set version=version+1 where id=?", main(f));
            return permit(c);
        }).when(source).prepare(any());
        doReturn(null).when(source).abort(any());
        var view = lifecycle.evaluate(tenant, main(f), Purpose.SETTLED);
        assertThat(view.stage()).isEqualTo(Stage.ABORTING);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
        doAnswer(invocation -> released(invocation.getArgument(0))).when(source).abort(any());
        assertThat(lifecycle.resume(tenant, view.operationId()).stage()).isEqualTo(Stage.ABORTED);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test void sqlFailureRollsBackEveryLocalClosingEffectAndKeepsTheIntentRetryable() {
        var f = create(); var sessionId = main(f); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        String constraint = "reject_end_" + sessionId.toString().replace("-", "");
        jdbc.execute("alter table guest_sessions add constraint " + constraint + " check (table_session_id <> '" + sessionId + "'::uuid or status='ACTIVE')");
        try {
            assertThatThrownBy(() -> lifecycle.evaluate(tenant, sessionId, Purpose.SETTLED)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSING);
            assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
            assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        } finally { jdbc.execute("alter table guest_sessions drop constraint " + constraint); }
        var op = operations.findByRestaurantIdAndSessionIdAndStageIn(tenant, sessionId, List.of(Stage.REQUESTED)).orElseThrow();
        assertThat(lifecycle.resume(tenant, op.getId()).stage()).isEqualTo(Stage.COMPLETED);
    }

    @Test void tenantAndSourceCallsUnderACallerTransactionAreRejected() {
        var f = create(); var sessionId = main(f); var view = settle(f);
        var foreignTenant = UUID.randomUUID();
        var operationId = view.operationId();
        assertThatThrownBy(() -> lifecycle.resume(foreignTenant, operationId)).isInstanceOf(SessionClosureConflictException.class);
        var transaction = new TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.execute(status -> lifecycle.evaluate(tenant, sessionId, Purpose.SETTLED)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"request_json", "request_digest", "restaurant_id", "result_json", "stage"})
    void databaseGuardsImmutableCommandReceiptAndTerminalState(String column) {
        var f = create(); var view = settle(f);
        String value = switch (column) {
            case "request_json", "result_json" -> "'{}'";
            case "request_digest" -> "'" + "b".repeat(64) + "'";
            case "restaurant_id" -> "'" + UUID.randomUUID() + "'::uuid";
            default -> "'REQUESTED'";
        };
        var operationId = view.operationId();
        assertThatThrownBy(() -> jdbc.update("update session_transition_operations set " + column + "=" + value + " where id=?", operationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from session_transition_operations where id=?", operationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentRetriesHaveOneIntentAndOneLocalResult() throws Exception {
        var f = create(); state(f, OrderSessionState.Knowledge.SETTLED, Set.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return lifecycle.evaluate(tenant, main(f), Purpose.SETTLED); });
            var second = pool.submit(() -> { start.await(); return lifecycle.evaluate(tenant, main(f), Purpose.SETTLED); });
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from session_transition_operations where table_session_id=?", Integer.class, main(f))).isEqualTo(1);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
    }
}
