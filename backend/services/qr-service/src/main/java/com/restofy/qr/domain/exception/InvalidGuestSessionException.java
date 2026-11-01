package com.restofy.qr.domain.exception;

public class InvalidGuestSessionException extends RuntimeException {
    public InvalidGuestSessionException() {
        super("Guest session is unavailable.");
    }
}
