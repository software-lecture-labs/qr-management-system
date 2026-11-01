package com.restofy.qr.domain.exception;

public class QrImageUnavailableException extends RuntimeException {
    public QrImageUnavailableException() {
        super("QR image rendering is unavailable until the public entry URL is configured.");
    }
}
