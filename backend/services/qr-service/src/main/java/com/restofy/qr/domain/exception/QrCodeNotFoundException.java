package com.restofy.qr.domain.exception;

public class QrCodeNotFoundException extends RuntimeException {
    public QrCodeNotFoundException() {
        super("QR code not found.");
    }
}
