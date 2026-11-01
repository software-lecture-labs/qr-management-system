package com.restofy.qr.domain.exception;

public class TableNotFoundException extends RuntimeException {
    public TableNotFoundException() {
        super("Table not found.");
    }
}
