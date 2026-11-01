package com.restofy.qr.domain.exception;

public class TableSessionConflictException extends RuntimeException {
    public TableSessionConflictException() {
        super("Table session cannot be opened in the current state.");
    }
}
