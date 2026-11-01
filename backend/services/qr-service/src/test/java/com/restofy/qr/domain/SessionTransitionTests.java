package com.restofy.qr.domain;

import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SessionTransitionTests {
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private Command command() {
        return new Command(UUID.randomUUID(), Purpose.SETTLED,
                new Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, NOW, NOW, NOW.plusSeconds(900), Map.of()), NOW);
    }

    @Test void snapshotDigestSurvivesInputMutationAndMapOrderAndContainsNoCredential() {
        var guests = new HashMap<UUID, GuestTarget>();
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        guests.put(first, new GuestTarget(1, GuestSessionStatus.ACTIVE, NOW, NOW.plusSeconds(900)));
        guests.put(second, new GuestTarget(0, GuestSessionStatus.ACTIVE, NOW, null));
        var c = command(); var p = c.snapshot();
        var snapshot = new Snapshot(p.restaurantId(), p.sessionId(), p.tableId(), 1, 2, NOW, NOW, null, guests);
        var frozen = new Command(c.operationId(), c.purpose(), snapshot, NOW);
        String digest = frozen.digest(); guests.clear();
        assertThat(frozen.snapshot().guests()).hasSize(2);
        var immutableGuests = snapshot.guests();
        assertThatThrownBy(immutableGuests::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(frozen.digest()).isEqualTo(digest).matches("[0-9a-f]{64}");
        assertThat(new Command(UUID.randomUUID(), c.purpose(), snapshot, NOW).digest()).isNotEqualTo(digest);
    }

    @ParameterizedTest @ValueSource(strings = {"foreignCommand", "expired", "future", "incomplete", "unfenced", "unknown", "blocked", "wrongEffect", "foreignGuest", "noRevision"})
    void onlyCurrentCompleteExactFencedAuthorityCanEndAParent(String kind) {
        var c = command();
        var proof = new Authority(kind.equals("foreignCommand") ? command() : c,
                kind.equals("unknown") ? SourceStatus.UNKNOWN : kind.equals("blocked") ? SourceStatus.BLOCKED : SourceStatus.AUTHORISED,
                kind.equals("wrongEffect") ? Effect.IDLE_MAIN : Effect.SETTLED_CLOSE,
                kind.equals("noRevision") ? 0 : 1, kind.equals("future") ? NOW.plusSeconds(1) : NOW,
                kind.equals("expired") ? NOW : NOW.plusSeconds(30), !kind.equals("incomplete"), !kind.equals("unfenced"),
                kind.equals("foreignGuest") ? Set.of(UUID.randomUUID()) : Set.of());
        assertThat(proof.permits(c, NOW)).isFalse();
    }

    @Test void validPermitAndIdlePurposeHaveDifferentEffects() {
        var c = command();
        assertThat(new Authority(c, SourceStatus.AUTHORISED, Effect.SETTLED_CLOSE, 1, NOW, NOW.plusSeconds(1), true, true, Set.of()).permits(c, NOW)).isTrue();
        var idle = new Command(c.operationId(), Purpose.IDLE, c.snapshot(), NOW);
        assertThat(new Authority(idle, SourceStatus.AUTHORISED, Effect.IDLE_MAIN, 1, NOW, NOW.plusSeconds(1), true, true, Set.of()).permits(idle, NOW)).isTrue();
        assertThat(new Authority(idle, SourceStatus.AUTHORISED, Effect.IDLE_GUESTS, 1, NOW, NOW.plusSeconds(1), true, true, Set.of()).permits(idle, NOW)).isTrue();
        assertThat(new Authority(idle, SourceStatus.AUTHORISED, Effect.SETTLED_CLOSE, 1, NOW, NOW.plusSeconds(1), true, true, Set.of()).permits(idle, NOW)).isFalse();
    }

    @Test void readOnlyOrderKnowledgeIsNeverInventedAndCannotReleaseAProtectedGuest() {
        var c = command(); var p = c.snapshot(); var guest = UUID.randomUUID();
        var unknown = OrderSessionState.unknown(p.restaurantId(), p.sessionId());
        assertThat(unknown.usableFor(p.restaurantId(), p.sessionId(), NOW)).isFalse();
        var state = new OrderSessionState(p.restaurantId(), p.sessionId(), OrderSessionState.Knowledge.OPEN_WORK, 1,
                NOW, NOW.plusSeconds(30), Set.of(guest), Map.of(), null);
        assertThat(state.protectsMain()).isTrue(); assertThat(state.protectsGuest(guest)).isTrue();
        assertThat(state.usableFor(UUID.randomUUID(), p.sessionId(), NOW)).isFalse();
        assertThat(state.usableFor(p.restaurantId(), p.sessionId(), NOW.plusSeconds(30))).isFalse();
        var restaurantId = p.restaurantId();
        var sessionId = p.sessionId();
        var until = NOW.plusSeconds(30);
        var protectedGuests = Set.of(guest);
        Map<UUID, Instant> noReleases = Map.of();
        var conflictingReleases = Map.of(guest, NOW);
        assertThatThrownBy(() -> new OrderSessionState(restaurantId, sessionId, OrderSessionState.Knowledge.NO_ORDERS,
                1, NOW, until, protectedGuests, noReleases, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSessionState(restaurantId, sessionId, OrderSessionState.Knowledge.OPEN_WORK,
                1, NOW, until, protectedGuests, conflictingReleases, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void localReceiptAndAbortAreExclusiveAndHistoryCannotBeReplayedAsANewTransition() {
        var operation = new SessionTransitionOperation(command(), "{}");
        assertThat(operation.getStage()).isEqualTo(Stage.REQUESTED);
        operation.attempt(NOW); assertThat(operation.getAttempts()).isEqualTo(1);
        var earlier = NOW.minusSeconds(1);
        assertThatThrownBy(() -> operation.attempt(earlier)).isInstanceOf(SessionClosureConflictException.class);
        operation.apply("{\"receipt\":true}");
        assertThatThrownBy(operation::requireAbort).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(operation::abort).isInstanceOf(SessionClosureConflictException.class);
        operation.acknowledge(); assertThat(operation.getStage()).isEqualTo(Stage.COMPLETED);
        assertThatThrownBy(() -> operation.apply("{}")).isInstanceOf(SessionClosureConflictException.class);
        var aborted = new SessionTransitionOperation(command(), "{}");
        aborted.requireAbort(); aborted.requireAbort(); aborted.abort();
        assertThat(aborted.getStage()).isEqualTo(Stage.ABORTED);
        assertThatThrownBy(aborted::acknowledge).isInstanceOf(SessionClosureConflictException.class);
    }

    @Test void onlyAFrozenMainCanExpireOrReopenAndNeitherOperationInventsActivity() {
        var main = new TableSession(UUID.randomUUID(), UUID.randomUUID(), NOW);
        var deadline = NOW.plusSeconds(900);
        var beforeDeadline = NOW.plusSeconds(899);
        assertThatThrownBy(() -> main.expireClosingAt(deadline)).isInstanceOf(SessionClosureConflictException.class);
        main.beginClosingAt(NOW);
        assertThatThrownBy(() -> main.expireClosingAt(beforeDeadline)).isInstanceOf(SessionClosureConflictException.class);
        main.reopenAfterTransition(NOW);
        assertThat(main.getLastActivityAt()).isEqualTo(NOW); assertThat(main.getClosingAt()).isNull();
        main.beginClosingAt(NOW.plusSeconds(900)); main.expireClosingAt(NOW.plusSeconds(900));
        assertThat(main.getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
        assertThatThrownBy(() -> main.reopenAfterTransition(deadline)).isInstanceOf(SessionClosureConflictException.class);
    }
}
