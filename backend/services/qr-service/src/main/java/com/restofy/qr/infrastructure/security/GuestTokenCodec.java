package com.restofy.qr.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class GuestTokenCodec {
    private final SecureRandom random = new SecureRandom();

    public String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid guest credential format.");
        }
        try {
            // Domain separation keeps QR and guest credentials distinct.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("restofy:guest:v1:" + token).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException _) {
            throw new IllegalStateException("Guest digest could not be computed.");
        }
    }
}
