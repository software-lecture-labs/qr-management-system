package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import java.util.UUID;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class QrCodeTests {
    private final UUID id = UUID.randomUUID();
    private final UUID restaurant = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();
    private final String digest = "a".repeat(64);
    private final String payload = "v1:" + "A".repeat(95);

    @Test
    void newCodeHasImmutableOwnershipAndActiveState() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        assertThat(code.getId()).isEqualTo(id);
        assertThat(code.getRestaurantId()).isEqualTo(restaurant);
        assertThat(code.getTableId()).isEqualTo(table);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(code.getVersion()).isZero();
        assertThat(code.getIssuedAt()).isNotNull();
        assertThat(code.getTokenDigest()).isEqualTo(digest);
        assertThat(code.getEncryptedPayload()).isEqualTo(payload);
        assertThat(code.getRevokedAt()).isNull();
        assertThat(code.getExpiresAt()).isNull();
    }

    @Test
    void activePermanentQrIsUsableFromItsIssueTimeWithoutChangingItsState() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        Instant issued = code.getIssuedAt();
        assertThat(code.isUsableAt(issued.minusNanos(1))).isFalse();
        assertThat(code.isUsableAt(issued)).isTrue();
        assertThat(code.isUsableAt(issued.plusSeconds(1_000_000))).isTrue();
        assertThat(code.getVersion()).isZero();
    }

    @Test
    void optionalExpiryRejectsTheExactExpiryInstantAndAnythingAfterIt() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        Instant expires = code.getIssuedAt().plusSeconds(60);
        ReflectionTestUtils.setField(code, "expiresAt", expires);
        assertThat(code.isUsableAt(expires.minusNanos(1))).isTrue();
        assertThat(code.isUsableAt(expires)).isFalse();
        assertThat(code.isUsableAt(expires.plusNanos(1))).isFalse();
        assertThat(code.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    void inactiveOrRevokedQrIsNotUsableEvenWithoutExpiry() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        Instant now = code.getIssuedAt().plusSeconds(1);
        code.changeActive(false, 0);
        assertThat(code.isUsableAt(now)).isFalse();
        code.changeActive(true, 0);
        assertThat(code.isUsableAt(now)).isTrue();
        code.revoke(0);
        assertThat(code.isUsableAt(now)).isFalse();
    }

    @Test
    void revocationRetainsIdentityAndSetsTimestampWithoutReactivating() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        code.revoke(0);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(code.getRevokedAt()).isAfterOrEqualTo(code.getIssuedAt());
        assertThat(code.getTokenDigest()).isEqualTo(digest);
        assertThat(code.getEncryptedPayload()).isEqualTo(payload);
        assertThatThrownBy(() -> code.revoke(0)).isInstanceOf(QrCodeConflictException.class);
        assertThatThrownBy(() -> code.changeActive(true, 0)).isInstanceOf(QrCodeConflictException.class);
        assertThatThrownBy(() -> code.changeActive(false, 0)).isInstanceOf(QrCodeConflictException.class);
    }

    @Test
    void reversibleDeactivationPreservesCredentialAndCannotBeRotatedWhileInactive() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        code.changeActive(false, 0);
        code.changeActive(false, 0);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.INACTIVE);
        assertThat(code.getRevokedAt()).isNull();
        assertThatThrownBy(() -> code.revoke(0)).isInstanceOf(QrCodeConflictException.class);
        code.changeActive(true, 0);
        code.changeActive(true, 0);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(code.getTokenDigest()).isEqualTo(digest);
        assertThat(code.getEncryptedPayload()).isEqualTo(payload);
        assertThatThrownBy(() -> code.changeActive(false, 1)).isInstanceOf(QrCodeConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1, Long.MAX_VALUE})
    void revocationRejectsInvalidOrStaleVersionWithoutChangingState(long version) {
        var code = new QrCode(id, restaurant, table, digest, payload);
        assertThatThrownBy(() -> code.revoke(version)).isInstanceOf(QrCodeConflictException.class);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(code.getRevokedAt()).isNull();
    }

    @Test
    void missingIdsCannotConstructCode() {
        assertThatThrownBy(() -> new QrCode(null, restaurant, table, digest, payload)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QrCode(id, null, table, digest, payload)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QrCode(id, restaurant, null, digest, payload)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"raw-token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void rejectsMalformedDigest(String value) {
        assertThatThrownBy(() -> new QrCode(id, restaurant, table, value, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"raw-token", "v2:content"})
    void rejectsUnprotectedPayload(String value) {
        assertThatThrownBy(() -> new QrCode(id, restaurant, table, digest, value)).isInstanceOf(IllegalArgumentException.class);
    }
}
