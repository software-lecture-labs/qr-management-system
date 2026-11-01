package com.restofy.waiter.domain.exception;

/**
 * A guest session raised more new calls than its limit allows (HTTP 429). Nothing was written; the guest can
 * try again after {@link #retryAfterSeconds()}.
 */
public class CallRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public CallRateLimitedException(long retryAfterSeconds) {
        super("Too many calls were raised in a short time; try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
