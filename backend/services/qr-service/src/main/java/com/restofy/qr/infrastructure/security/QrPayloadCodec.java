package com.restofy.qr.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QrPayloadCodec {
    private static final int NONCE_BYTES = 12;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public QrPayloadCodec(@Value("${qr.payload.encryption-key}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException("QR payload encryption requires a Base64-encoded 32-byte key.");
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("QR payload encryption requires a Base64-encoded 32-byte key.");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String token) {
        requireToken(token);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("QR digest could not be computed.");
        }
    }

    public String encrypt(String token, UUID restaurantId, UUID tableId, UUID qrId) {
        requireToken(token);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(restaurantId, tableId, qrId));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
            byte[] envelope = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("QR payload could not be protected.");
        }
    }

    // Internal storage primitive used only after the management tenant scope is verified.
    public String decrypt(String payload, UUID restaurantId, UUID tableId, UUID qrId) {
        if (payload == null || !payload.matches("v1:[A-Za-z0-9_-]{95}")) {
            throw new IllegalStateException("QR payload could not be recovered.");
        }
        byte[] envelope = Base64.getUrlDecoder().decode(payload.substring(3));
        try {
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(envelope, NONCE_BYTES)));
            cipher.updateAAD(context(restaurantId, tableId, qrId));
            return new String(cipher.doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("QR payload could not be recovered.");
        }
    }

    private static byte[] context(UUID restaurantId, UUID tableId, UUID qrId) {
        return ("restofy:qr:v1:" + restaurantId + ":" + tableId + ":" + qrId).getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid QR credential format.");
        }
    }
}
