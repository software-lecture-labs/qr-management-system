package com.restofy.qr.domain.exception;

public class SessionClosureConflictException extends RuntimeException {
    public SessionClosureConflictException() {
        super("Session closure conflicts with the current state. Reload and reconcile before retrying.");
    }
}
