package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class IdleLifecycleTests {
    private final Instant start = Instant.parse("2026-10-19T10:00:00Z");
    private final UUID restaurant = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();

    @Test
    void guestWindowsAreIndependentAndTheMainTracksLatestActivity() {
        var main = new TableSession(restaurant, table, start);
        var a = new GuestSession(restaurant, main.getId(), "a".repeat(64), start);
        var b = new GuestSession(restaurant, main.getId(), "b".repeat(64), start.plusSeconds(300));
        main.recordActivityAt(start.plusSeconds(300));
        assertThat(a.getExpiresAt()).isEqualTo(start.plusSeconds(900));
        assertThat(b.getExpiresAt()).isEqualTo(start.plusSeconds(1200));
        assertThat(main.getIdleExpiresAt()).isEqualTo(b.getExpiresAt());
        assertThat(a.expireIdleAt(start.plusSeconds(900).minusNanos(1))).isFalse();
        assertThat(a.expireIdleAt(start.plusSeconds(900))).isTrue();
        assertThat(main.expireIdleAt(start.plusSeconds(900))).isFalse();
        assertThat(b.isUsableAt(start.plusSeconds(900))).isTrue();
        assertThat(main.expireIdleAt(start.plusSeconds(1200))).isTrue();
        b.expireWithMainAt(start.plusSeconds(1200));
        assertThat(main.getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
        assertThat(b.getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
    }

    @Test
    void boundaryIsExclusiveAndExpiredOrBackdatedActivityCannotReviveAnIdentity() {
        var main = new TableSession(restaurant, table, start);
        var guest = new GuestSession(restaurant, main.getId(), "a".repeat(64), start);
        Instant deadline = start.plusSeconds(900);
        assertThatThrownBy(() -> guest.recordActivityAt(deadline)).isInstanceOf(InvalidGuestSessionException.class);
        assertThatThrownBy(() -> main.recordActivityAt(deadline)).isInstanceOf(TableSessionConflictException.class);
        Instant backwards = start.minusNanos(1);
        assertThatThrownBy(() -> guest.recordActivityAt(backwards)).isInstanceOf(InvalidGuestSessionException.class);
        assertThatThrownBy(() -> main.recordActivityAt(backwards)).isInstanceOf(TableSessionConflictException.class);
        assertThat(guest.expireIdleAt(deadline)).isTrue();
        assertThat(guest.expireIdleAt(deadline.plusSeconds(1))).isFalse();
        assertThat(main.expireIdleAt(deadline)).isTrue();
        assertThat(main.expireIdleAt(deadline.plusSeconds(1))).isFalse();
        guest.expireWithMainAt(deadline.plusSeconds(1));
        assertThat(guest.getEndedAt()).isEqualTo(deadline);
        assertThat(main.getClosedAt()).isEqualTo(deadline);
    }

    @Test
    void activityUsesStoredMicrosecondsWithoutRoundingAnEarlyEventAcrossTheBoundary() {
        var guest = new GuestSession(restaurant, UUID.randomUUID(), "a".repeat(64), start);
        Instant justBefore = start.plusSeconds(900).minusNanos(1);
        guest.recordActivityAt(justBefore);
        assertThat(guest.getLastActivityAt()).isEqualTo(start.plusSeconds(900).minusNanos(1000));
        assertThat(guest.getExpiresAt()).isEqualTo(guest.getLastActivityAt().plusSeconds(900));
        assertThat(guest.isUsableAt(start.plusSeconds(900))).isTrue();
    }

    @Test
    void legacyNullDeadlinesAreNotInventedUntilExplicitActivityAndFutureDataCannotBeEnded() {
        var main = new TableSession(restaurant, table, start);
        var guest = new GuestSession(restaurant, main.getId(), "a".repeat(64), start);
        ReflectionTestUtils.setField(main, "idleExpiresAt", null);
        ReflectionTestUtils.setField(guest, "expiresAt", null);
        assertThat(main.expireIdleAt(start.plusSeconds(9000))).isFalse();
        assertThat(guest.expireIdleAt(start.plusSeconds(9000))).isFalse();
        Instant earlier = start.minusSeconds(1);
        assertThatThrownBy(() -> guest.expireWithMainAt(earlier)).isInstanceOf(InvalidGuestSessionException.class);
        main.recordActivityAt(start.plusSeconds(9000));
        guest.recordActivityAt(start.plusSeconds(9000));
        assertThat(main.getIdleExpiresAt()).isEqualTo(start.plusSeconds(9900));
        assertThat(guest.getExpiresAt()).isEqualTo(main.getIdleExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSING", "CLOSED", "EXPIRED"})
    void aTerminalOrClosingMainCannotBeRefreshedOrExpiredAgain(String state) {
        var main = new TableSession(restaurant, table, start);
        ReflectionTestUtils.setField(main, "status", TableSessionStatus.valueOf(state));
        assertThatThrownBy(() -> main.recordActivityAt(start)).isInstanceOf(TableSessionConflictException.class);
        assertThat(main.expireIdleAt(start.plusSeconds(900))).isFalse();
    }

    @Test
    void onlyAnActiveOccupiedTableCanBeReleasedAfterVerifiedIdleExpiry() {
        var dining = new DiningTable(restaurant, "1", null, null);
        assertThatThrownBy(dining::releaseAfterIdle).isInstanceOf(TableConflictException.class);
        dining.occupy();
        dining.releaseAfterIdle();
        assertThat(dining.getOperationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(dining.isActive()).isTrue();
        ReflectionTestUtils.setField(dining, "active", false);
        assertThatThrownBy(dining::releaseAfterIdle).isInstanceOf(TableConflictException.class);
    }
}
