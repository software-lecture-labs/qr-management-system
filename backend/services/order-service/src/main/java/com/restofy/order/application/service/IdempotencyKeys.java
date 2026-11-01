package com.restofy.order.application.service;

import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Shared rules for {@code Idempotency-Key} headers and request digests. */
final class IdempotencyKeys {

    static final String CART_ITEM = "ADD_CART_ITEM";

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{8,120}");

    private IdempotencyKeys() {
    }

    /** Validates a key. A key is a client-generated random value (a UUID is ideal), never derived from user data. */
    static String require(String key) {
        if (key == null || !FORMAT.matcher(key).matches()) {
            throw invalid();
        }
        return key;
    }

    /** Validates a key that may be absent; returns {@code null} when the header was not sent. */
    static String optional(String key) {
        return key == null ? null : require(key);
    }

    static String sha256(String canonicalRequest) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                "An Idempotency-Key of 8-120 characters (letters, digits, . _ : -) is required");
    }
}
