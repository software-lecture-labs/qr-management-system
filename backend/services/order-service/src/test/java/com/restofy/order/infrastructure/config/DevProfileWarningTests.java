package com.restofy.order.infrastructure.config;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning is loud, says what the dev profile means, names no credential and exists only in the dev profile. */
@ExtendWith(OutputCaptureExtension.class)
class DevProfileWarningTests {

    @Test
    void announcingWritesTheWholeBlockAsWarnings(CapturedOutput output) {
        new DevProfileWarning().announce();

        for (String line : DevProfileWarning.LINES) {
            assertTrue(output.getAll().contains(line), "missing line: " + line);
        }
        long warnings = output.getAll().lines().filter(l -> l.contains("WARN")).count();
        assertEquals(DevProfileWarning.LINES.size(), warnings, "every line of the block is a WARN");
    }

    @Test
    void theBlockSaysWhatTheDevProfileMeans() {
        String text = String.join(" ", DevProfileWarning.LINES).replaceAll("[#\\s]+", " ");

        assertTrue(text.contains("'dev' PROFILE"));
        assertTrue(text.contains("NO AUTHENTICATION"));
        assertTrue(text.contains("ADMIN"));
        assertTrue(text.contains("API Gateway"));
        assertTrue(text.contains("Never use this profile on a shared"));
    }

    @Test
    void theBlockNeverPrintsACredential() {
        String text = String.join("\n", DevProfileWarning.LINES);

        for (String secret : new String[]{DevFixtures.TOKEN_A1, DevFixtures.STAFF_ADMIN_A,
                InMemoryServiceIdentityAdapter.KITCHEN_CREDENTIAL, InMemoryServiceIdentityAdapter.QR_CREDENTIAL}) {
            assertFalse(text.contains(secret), secret);
        }
    }

    @Test
    void onlyTheDevProfileGetsIt() {
        Profile profile = DevProfileWarning.class.getAnnotation(Profile.class);

        assertArrayEquals(new String[]{"dev"}, profile.value());
    }
}
