package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class QrPayloadCodecTests {
    // Public test fixture, never an application/default key.
    static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final QrPayloadCodec codec = new QrPayloadCodec(TEST_KEY);
    private final UUID restaurant = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();
    private final UUID qr = UUID.randomUUID();

    @Test
    void tokensAreUrlSafeAndUniqueWithStableSha256Digest() {
        var tokens = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String token = codec.generateToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(tokens.add(token)).isTrue();
        }
        assertThat(codec.digest("A".repeat(43)))
                .isEqualTo("0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a");
    }

    @Test
    void encryptionUsesFreshNonceAndRecoversOnlyWithSameKeyAndBinding() {
        String token = codec.generateToken();
        String first = codec.encrypt(token, restaurant, table, qr);
        String second = codec.encrypt(token, restaurant, table, qr);
        assertThat(first).matches("v1:[A-Za-z0-9_-]{95}").isNotEqualTo(second).doesNotContain(token);
        assertThat(codec.decrypt(first, restaurant, table, qr)).isEqualTo(token);
        assertThat(codec.decrypt(second, restaurant, table, qr)).isEqualTo(token);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> codec.decrypt(first, other, table, qr)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> codec.decrypt(first, restaurant, other, qr)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> codec.decrypt(first, restaurant, table, other)).isInstanceOf(IllegalStateException.class);
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        var wrongKey = new QrPayloadCodec(Base64.getEncoder().encodeToString(otherKey));
        assertThatThrownBy(() -> wrongKey.decrypt(first, restaurant, table, qr)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingCannotProduceRecoveredCredential() {
        String token = codec.generateToken();
        String payload = codec.encrypt(token, restaurant, table, qr);
        byte[] envelope = Base64.getUrlDecoder().decode(payload.substring(3));
        envelope[20] ^= 1;
        String altered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        assertThatThrownBy(() -> codec.decrypt(altered, restaurant, table, qr))
                .isInstanceOf(IllegalStateException.class).hasMessage("QR payload could not be recovered.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64!", "c2hvcnQ=", " ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void missingOrInvalidKeysFailWithoutEchoingInput(String key) {
        assertThatThrownBy(() -> new QrPayloadCodec(key)).isInstanceOf(IllegalStateException.class)
                .hasMessage("QR payload encryption requires a Base64-encoded 32-byte key.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"v2:unknown", "v1:garbage", "v1:+++", "sensitive-input"})
    void malformedStorageIsRejectedWithoutEchoingPayload(String payload) {
        assertThatThrownBy(() -> codec.decrypt(payload, restaurant, table, qr))
                .isInstanceOf(IllegalStateException.class).hasMessage("QR payload could not be recovered.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "spaces are invalid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!"})
    void invalidRawCredentialsCannotBeDigestedOrStored(String token) {
        assertThatThrownBy(() -> codec.digest(token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encrypt(token, restaurant, table, qr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dtoStringRepresentationRedactsRawCredential() {
        String token = codec.generateToken();
        var response = new CreatedQrCodeResponse(qr, restaurant, table, QrCodeStatus.ACTIVE, 0, Instant.now(), null, token);
        assertThat(response).hasToString("CreatedQrCodeResponse[id=" + qr + ", qrToken=[REDACTED]]");
    }
}
