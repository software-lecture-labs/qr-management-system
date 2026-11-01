package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class GuestSessionTests {
    private final UUID restaurant = UUID.randomUUID();
    private final UUID main = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    void newGuestHasOwnedIdentityActivityAndTheApprovedFifteenMinuteExpiry() {
        var guest = new GuestSession(restaurant, main, "a".repeat(64), now);
        assertThat(guest.getId()).isNotNull();
        assertThat(guest.getRestaurantId()).isEqualTo(restaurant);
        assertThat(guest.getTableSessionId()).isEqualTo(main);
        assertThat(guest.getTokenDigest()).isEqualTo("a".repeat(64));
        assertThat(guest.getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        assertThat(guest.getJoinedAt()).isEqualTo(now).isEqualTo(guest.getLastActivityAt());
        assertThat(guest.getExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(guest.getEndedAt()).isNull();
        assertThat(guest.getVersion()).isZero();
        assertThat(guest.isUsableAt(now)).isTrue();
        assertThat(guest.isUsableAt(now.minusNanos(1))).isFalse();
        assertThat(new GuestSession(restaurant, main, "b".repeat(64), now).getId()).isNotEqualTo(guest.getId());
    }

    @Test
    void ownershipAndTimeAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new GuestSession(null, main, "a".repeat(64), now));
        assertThatNullPointerException().isThrownBy(() -> new GuestSession(restaurant, null, "a".repeat(64), now));
        assertThatNullPointerException().isThrownBy(() -> new GuestSession(restaurant, main, "a".repeat(64), null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void rawOrMalformedCredentialsCannotBeStoredAsDigest(String digest) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GuestSession(restaurant, main, digest, now));
    }

    @Test
    void explicitExpiryIsExclusiveAndFutureActivityOrEndedStateRejectsUse() {
        var guest = new GuestSession(restaurant, main, "a".repeat(64), now);
        ReflectionTestUtils.setField(guest, "expiresAt", now.plusSeconds(60));
        assertThat(guest.isUsableAt(now.plusSeconds(60).minusNanos(1))).isTrue();
        assertThat(guest.isUsableAt(now.plusSeconds(60))).isFalse();
        ReflectionTestUtils.setField(guest, "lastActivityAt", now.plusSeconds(1));
        assertThat(guest.isUsableAt(now)).isFalse();
        ReflectionTestUtils.setField(guest, "endedAt", now.plusSeconds(1));
        assertThat(guest.isUsableAt(now.plusSeconds(2))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "EXPIRED"})
    void terminalGuestIsUnusableEvenIfItsDatesWouldBeValid(String state) {
        var guest = new GuestSession(restaurant, main, "a".repeat(64), now);
        ReflectionTestUtils.setField(guest, "status", GuestSessionStatus.valueOf(state));
        assertThat(guest.isUsableAt(now)).isFalse();
    }
}
