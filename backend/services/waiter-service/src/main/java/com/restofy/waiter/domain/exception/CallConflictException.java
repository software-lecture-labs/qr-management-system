package com.restofy.waiter.domain.exception;

/**
 * The command conflicts with the current state of its target or with another command (HTTP 409). The stable
 * {@code code} tells clients which conflict it is; they must re-read the call before deciding what to do.
 */
public class CallConflictException extends RuntimeException {

    private final String code;

    public CallConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
