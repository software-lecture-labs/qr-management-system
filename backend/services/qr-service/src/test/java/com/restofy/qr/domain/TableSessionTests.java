package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableSessionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class TableSessionTests {
    @Test
    void openGuardHonoursExplicitDeadlineAndRejectsFutureOrTerminalState() {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        var session = new TableSession(UUID.randomUUID(), UUID.randomUUID(), now);
        assertThat(session.isOpenAt(now)).isTrue();
        assertThat(session.isOpenAt(now.minusNanos(1))).isFalse();
        ReflectionTestUtils.setField(session, "idleExpiresAt", now.plusSeconds(60));
        assertThat(session.isOpenAt(now.plusSeconds(60).minusNanos(1))).isTrue();
        assertThat(session.isOpenAt(now.plusSeconds(60))).isFalse();
        ReflectionTestUtils.setField(session, "lastActivityAt", now.plusSeconds(1));
        assertThat(session.isOpenAt(now)).isFalse();
        ReflectionTestUtils.setField(session, "closingAt", now);
        assertThat(session.isOpenAt(now.plusSeconds(2))).isFalse();
        ReflectionTestUtils.setField(session, "closingAt", null);
        ReflectionTestUtils.setField(session, "closedAt", now);
        assertThat(session.isOpenAt(now.plusSeconds(2))).isFalse();
        ReflectionTestUtils.setField(session, "closedAt", null);
        ReflectionTestUtils.setField(session, "status", TableSessionStatus.CLOSED);
        assertThat(session.isOpenAt(now.plusSeconds(2))).isFalse();
    }

    @Test
    void newMainSessionHasOwnedIdentityAndTheApprovedFifteenMinuteIdleDeadline() {
        UUID restaurant = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        var session = new TableSession(restaurant, table, now);
        assertThat(session.getId()).isNotNull();
        assertThat(session.getRestaurantId()).isEqualTo(restaurant);
        assertThat(session.getTableId()).isEqualTo(table);
        assertThat(session.getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(session.getVersion()).isZero();
        assertThat(session.getOpenedAt()).isEqualTo(now);
        assertThat(session.getLastActivityAt()).isEqualTo(now);
        assertThat(session.getIdleExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(session.getClosingAt()).isNull();
        assertThat(session.getClosedAt()).isNull();
        assertThat(session.getClosedByUserId()).isNull();
        assertThat(new TableSession(restaurant, table, now).getId()).isNotEqualTo(session.getId());
    }

    @Test
    void missingOwnershipOrTimeCannotConstructMainSession() {
        UUID restaurant = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatNullPointerException().isThrownBy(() -> new TableSession(null, table, now));
        assertThatNullPointerException().isThrownBy(() -> new TableSession(restaurant, null, now));
        assertThatNullPointerException().isThrownBy(() -> new TableSession(restaurant, table, null));
    }
}
