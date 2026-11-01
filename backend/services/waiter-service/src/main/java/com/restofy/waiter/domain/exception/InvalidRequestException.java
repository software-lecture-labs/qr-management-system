package com.restofy.waiter.domain.exception;

/** A query parameter is outside the allow-list or its allowed range. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
