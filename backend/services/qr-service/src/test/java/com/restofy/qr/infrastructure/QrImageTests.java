package com.restofy.qr.infrastructure;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.restofy.qr.domain.exception.QrImageUnavailableException;
import com.restofy.qr.infrastructure.config.QrEntryUrl;
import com.restofy.qr.infrastructure.image.QrPngRenderer;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class QrImageTests {
    private static final String TOKEN = "A".repeat(43);

    private QrEntryUrl entry(String url, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new QrEntryUrl(url, environment);
    }

    static String decode(byte[] png) throws Exception {
        return decode(png, true);
    }

    private static String decode(byte[] png, boolean pure) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).isEqualTo(512);
        var pixels = image.getRGB(0, 0, 512, 512, null, 0, 512);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(512, 512, pixels)));
        return new QRCodeReader().decode(bitmap,
                pure ? Map.of(DecodeHintType.PURE_BARCODE, true) : Map.of()).getText();
    }

    @Test
    void actualPngDecodesToExactHttpsUrlAndTokenWithQuietZoneAndStableBytes() throws Exception {
        var renderer = new QrPngRenderer();
        String payload = entry("https://guest.example.test/qr", "prod").forToken(TOKEN);
        byte[] first = renderer.render(payload);
        assertThat(first).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10)
                .hasSizeLessThan(100_000);
        assertThat(decode(first)).isEqualTo("https://guest.example.test/qr#token=" + TOKEN);
        assertThat(decode(first, false)).isEqualTo(payload);
        assertThat(renderer.render(payload)).isEqualTo(first);
        var image = ImageIO.read(new ByteArrayInputStream(first));
        for (int pixel = 0; pixel < 512; pixel++) {
            assertThat(image.getRGB(pixel, 0)).isEqualTo(0xFFFFFFFF);
            assertThat(image.getRGB(0, pixel)).isEqualTo(0xFFFFFFFF);
        }
    }

    @Test
    void longestConfiguredAddressStillProducesReadableQr() throws Exception {
        String prefix = "https://guest.example.test/";
        String url = prefix + "a".repeat(512 - prefix.length());
        String payload = entry(url, "prod").forToken(TOKEN);
        assertThat(decode(new QrPngRenderer().render(payload))).isEqualTo(payload);
    }

    @Test
    void pristineRasterDecodingHandlesTheReproducedDetectorSamplingCase() throws Exception {
        // Public deterministic fixture, not an application QR credential. The generic
        // camera detector mis-samples this image; pure raster extraction checks its
        // actual QR modules, including the same checksum/error correction decoder.
        String payload = "https://guest.example.test/qr#token=ZmwNcf6hZ4xIm3xO7QLBDaFyp9K5PQS2Fi20AGvKsl4";
        assertThat(decode(new QrPngRenderer().render(payload))).isEqualTo(payload);
    }

    @Test
    void deterministicTokenSamplesRoundTripThroughPristinePngs() throws Exception {
        var random = new Random(2404);
        var renderer = new QrPngRenderer();
        for (int sample = 0; sample < 64; sample++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String payload = "https://guest.example.test/qr#token=" + token;
            assertThat(decode(renderer.render(payload))).isEqualTo(payload);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void loopbackHttpIsAllowedOnlyForLocalDevelopment(String profile) {
        assertThat(entry("http://localhost:5000/qr", profile).forToken(TOKEN)).startsWith("http://localhost:5000/qr#token=");
        assertThat(entry("http://127.0.0.1:5000/qr", profile).forToken(TOKEN)).startsWith("http://127.0.0.1:5000/qr#token=");
    }

    @Test
    void productionAndUnspecifiedProfilesRejectEvenLoopbackHttp() {
        for (String[] profiles : new String[][]{{"prod"}, {}, {"prod", "dev"}}) {
            assertThatThrownBy(() -> entry("http://localhost:5000/qr", profiles)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://guest.example.test/qr", "javascript:alert(1)", "/qr", "//guest.example.test/qr",
            "https://user:secret@guest.example.test/qr", "https://guest.example.test/qr?token=sensitive",
            "https://guest.example.test/qr#token=sensitive", " https://guest.example.test/qr", "https://guest.example.test/\r\nInjected",
            "https://guest.example.test:65536/qr", "https://guest.example.test:0/qr", "https:///qr", " "})
    void unsafeAddressesFailAtStartupWithoutEchoingConfiguration(String url) {
        assertThatThrownBy(() -> entry(url, "dev")).isInstanceOf(IllegalStateException.class)
                .hasMessage("QR public entry URL must be a bounded HTTPS URL without credentials, query or fragment; loopback HTTP is allowed only in dev/test.")
                .hasNoCause();
    }

    @Test
    void oversizedUrlAndAsciiExpansionAreRejected() {
        String oversized = "https://guest.example.test/" + "a".repeat(512);
        String expanded = "https://guest.example.test/" + "ü".repeat(100);
        assertThatThrownBy(() -> entry(oversized, "prod"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> entry(expanded, "prod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingAddressDisablesRenderingWithoutInventingAPublicTarget() {
        var url = entry("", "prod");
        assertThatThrownBy(() -> url.forToken(TOKEN)).isInstanceOf(QrImageUnavailableException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "../../secret", "A?inject"})
    void onlyValidQrTokensCanBeAppended(String token) {
        var url = entry("https://guest.example.test/qr", "prod");
        assertThatThrownBy(() -> url.forToken(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void invalidRendererPayloadsAreRejected(String payload) {
        var renderer = new QrPngRenderer();
        assertThatThrownBy(() -> renderer.render(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rendererEnforcesItsInputBound() {
        var renderer = new QrPngRenderer();
        String oversized = "a".repeat(1025);
        assertThatThrownBy(() -> renderer.render(oversized)).isInstanceOf(IllegalArgumentException.class);
    }
}
