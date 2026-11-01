package com.restofy.waiter.domain.exception;

/**
 * The requested table or order does not exist in the caller's restaurant. Another restaurant's id looks
 * exactly the same, so the existence of foreign objects is never revealed.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
