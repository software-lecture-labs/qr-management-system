package com.restofy.order.domain.exception;

import java.util.Map;

/** A rule violation that maps to a documented API error. Messages must never carry secrets or internals. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
