package com.restofy.waiter.infrastructure.client;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs one downstream call and translates every failure into a {@link DependencyException}. A request is
 * never repeated here: automatic retries of non-idempotent calls are forbidden by docs/SECURITY.md.
 */
public final class DownstreamCalls {

    private static final Logger log = LoggerFactory.getLogger(DownstreamCalls.class);

    private DownstreamCalls() {
    }

    /**
     * A definite refusal by the downstream service (400, 404, 409, 422): the request was understood and not
     * applied. Everything else, including 401/403, is no usable answer.
     */
    public static boolean isDefiniteRefusal(int status) {
        return status == HttpStatus.BAD_REQUEST.value() || status == HttpStatus.NOT_FOUND.value()
                || status == HttpStatus.CONFLICT.value() || status == HttpStatus.UNPROCESSABLE_ENTITY.value();
    }

    /** The call must produce a body; an empty body or a 404 is a failure. */
    public static <T> T execute(String dependency, Supplier<T> call) {
        try {
            return requireBody(dependency, call.get());
        } catch (RestClientException error) {
            throw translate(dependency, error);
        }
    }

    /** A 404 is a legitimate "not found" answer for lookups by id. */
    public static <T> Optional<T> executeOptional(String dependency, Supplier<T> call) {
        try {
            return Optional.of(requireBody(dependency, call.get()));
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return Optional.empty();
            throw translate(dependency, error);
        } catch (RestClientException error) {
            throw translate(dependency, error);
        }
    }

    private static <T> T requireBody(String dependency, T body) {
        if (body == null) {
            throw new DependencyException(dependency, DependencyFailure.BAD_RESPONSE,
                    dependency + " service returned an empty body");
        }
        return body;
    }

    /** Translates a failed call into the dependency error that names why no usable answer arrived. */
    public static DependencyException translate(String dependency, RestClientException error) {
        DependencyFailure failure = classify(error);
        log.warn("Downstream '{}' call failed: {} ({})", dependency, failure, error.getClass().getSimpleName());
        String message = switch (failure) {
            case TIMEOUT -> dependency + " service did not answer in time";
            case UNAVAILABLE -> dependency + " service is unavailable";
            case REJECTED -> dependency + " service rejected the request from the waiter service";
            case BAD_RESPONSE -> dependency + " service returned an unusable response";
        };
        return new DependencyException(dependency, failure, message, error);
    }

    private static DependencyFailure classify(RestClientException error) {
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                return DependencyFailure.REJECTED;
            }
            return status >= HttpStatus.INTERNAL_SERVER_ERROR.value()
                    ? DependencyFailure.UNAVAILABLE : DependencyFailure.BAD_RESPONSE;
        }
        if (hasCause(error, HttpTimeoutException.class) || hasCause(error, SocketTimeoutException.class)) {
            return DependencyFailure.TIMEOUT;
        }
        if (error instanceof ResourceAccessException) return DependencyFailure.UNAVAILABLE;
        if (hasCause(error, IOException.class) && !hasCause(error, HttpMessageNotReadableException.class)) {
            return DependencyFailure.UNAVAILABLE;
        }
        return DependencyFailure.BAD_RESPONSE;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
