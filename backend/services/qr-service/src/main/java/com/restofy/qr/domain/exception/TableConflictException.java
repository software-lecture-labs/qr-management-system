package com.restofy.qr.domain.exception;

public class TableConflictException extends RuntimeException {
    public TableConflictException(String message) {
        super(message);
    }
}
