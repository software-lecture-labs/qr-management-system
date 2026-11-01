package com.restofy.order.application.service;

import com.restofy.order.domain.InputLimits;
import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the optional reason of a cancellation: any number of catalog codes (each at most once) and/or a short plain
 * text. Nothing is required; "no reason" is a valid answer. A code is an identifier and must match exactly; the text is
 * untrusted user input, so it is stripped of surrounding whitespace, limited and must not contain control characters.
 * It is stored and returned as plain text and is never interpreted.
 */
final class CancellationReasons {

    static final int MAX_TEXT_LENGTH = InputLimits.MAX_TEXT_LENGTH;
    private static final String PARAMETER = "parameter";

    /** The validated reason: catalog codes in the order given, and the free text (null when none). */
    record Parsed(List<CancellationReasonCode> codes, String text) {

        boolean isEmpty() {
            return codes.isEmpty() && text == null;
        }
    }

    private CancellationReasons() {
    }

    static Parsed parse(List<String> codes, String text) {
        List<CancellationReasonCode> parsed = new ArrayList<>();
        Set<CancellationReasonCode> seen = new HashSet<>();
        if (codes != null) {
            for (String code : codes) {
                CancellationReasonCode known = CancellationReasonCode.parse(code).orElseThrow(() ->
                        new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown cancellation reason code",
                                Map.of(PARAMETER, "reasonCodes", "rule", "must be one of " + catalog())));
                if (!seen.add(known)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A reason code may be given only once",
                            Map.of(PARAMETER, "reasonCodes", "rule", "must not repeat a code"));
                }
                parsed.add(known);
            }
        }
        return new Parsed(List.copyOf(parsed), cleanText(text));
    }

    /** Trimmed text, or null when blank. */
    static String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The reason text is too long",
                    Map.of(PARAMETER, "reasonText", "rule", "at most " + MAX_TEXT_LENGTH + " characters"));
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The reason text contains control characters",
                    Map.of(PARAMETER, "reasonText", "rule", "must be plain text"));
        }
        return trimmed;
    }

    private static String catalog() {
        return String.join(", ", Arrays.stream(CancellationReasonCode.values()).map(Enum::name).toList());
    }
}
