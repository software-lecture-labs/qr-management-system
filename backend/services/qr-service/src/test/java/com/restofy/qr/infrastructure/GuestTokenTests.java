package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.response.OpenedGuestSessionResponse;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class GuestTokenTests {
    private final GuestTokenCodec tokens = new GuestTokenCodec();

    @Test
    void generatedCredentialsAreDistinctAndDigestIsDomainSeparatedFromQr() throws Exception {
        var values = new HashSet<String>();
        for (int index = 0; index < 64; index++) {
            String token = tokens.generateToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            values.add(token);
        }
        assertThat(values).hasSize(64);
        String token = "A".repeat(43);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("restofy:guest:v1:" + token).getBytes(StandardCharsets.US_ASCII)));
        assertThat(tokens.digest(token)).isEqualTo(expected).hasSize(64);
        assertThat(tokens.digest(token)).isNotEqualTo(new QrPayloadCodec("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").digest(token));
    }

    @Test
    void responseNeverPrintsGuestCredential() {
        String token = tokens.generateToken();
        assertThat(new OpenedGuestSessionResponse(null, null, token)).hasToString("OpenedGuestSessionResponse[guestToken=[REDACTED]]");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", " AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
    void malformedCredentialsAreRejected(String token) {
        assertThatIllegalArgumentException().isThrownBy(() -> tokens.digest(token));
    }
}
