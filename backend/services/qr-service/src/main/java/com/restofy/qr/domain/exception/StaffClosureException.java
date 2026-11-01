package com.restofy.qr.domain.exception;

import org.springframework.http.HttpStatus;

/** Fixed contract codes; never reflects a reason, token or upstream response. */
public class StaffClosureException extends RuntimeException {
    public enum Code {
        SESSION_NOT_FOUND, SESSION_TABLE_MISMATCH, SESSION_VERSION_CONFLICT,
        SESSION_NOT_OPEN, SESSION_ALREADY_CLOSED, CLOSURE_BLOCKED, COMMAND_CONFLICT, COMMAND_NOT_FOUND
    }

    private final Code code;
    public StaffClosureException(Code code) { super("The staff closure request could not be applied."); this.code = code; }
    public Code code() { return code; }
    public HttpStatus status() {
        return switch (code) {
            case SESSION_NOT_FOUND, SESSION_TABLE_MISMATCH, COMMAND_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
    }
}
