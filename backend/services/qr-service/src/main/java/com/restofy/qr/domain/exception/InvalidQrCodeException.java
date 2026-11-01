package com.restofy.qr.domain.exception;

public class InvalidQrCodeException extends RuntimeException {
    public InvalidQrCodeException() {
        super("QR code is invalid or unavailable.");
    }
}
