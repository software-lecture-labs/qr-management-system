package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.exception.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Idempotency-Key validation and the request digest that detects "same key, different request". */
public final class IdempotencyKeys {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,120}");

    private IdempotencyKeys() {
    }

    /** Every mutation needs a client-chosen key, 8 to 120 characters of letters, digits and {@code . _ : -}. */
    public static String require(String key) {
        if (key == null || !VALID.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key header is required: 8 to 120 characters of letters, digits and . _ : -");
        }
        return key;
    }

    /** SHA-256 over the parts, separated by a character that cannot occur in any of them. */
    public static String digest(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) canonical.append(part == null ? "" : part).append('\u001f');
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
