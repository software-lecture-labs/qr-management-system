package com.restofy.qr.domain.exception;

public class SessionClosureUnavailableException extends RuntimeException {
    public SessionClosureUnavailableException() { super("Verified session closure evidence is unavailable."); }
}
