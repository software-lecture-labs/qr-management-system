package com.restofy.waiter.domain.exception;

/**
 * A dependency (qr, order) failed. A failure is never turned into an empty result: callers see an error
 * instead of an "empty table" or "no orders". The message must stay free of hosts, URLs and bodies.
 */
public class DependencyException extends RuntimeException {

    private final String dependency;
    private final DependencyFailure failure;

    public DependencyException(String dependency, DependencyFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.dependency = dependency;
        this.failure = failure;
    }

    public DependencyException(String dependency, DependencyFailure failure, String message) {
        this(dependency, failure, message, null);
    }

    public String dependency() {
        return dependency;
    }

    public DependencyFailure failure() {
        return failure;
    }
}
