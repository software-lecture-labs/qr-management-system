package com.restofy.waiter.infrastructure.client;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownstreamCallsTests {

    private static DependencyFailure failureOf(Supplier<?> call) {
        return assertThrows(DependencyException.class, () -> DownstreamCalls.execute("qr", call)).failure();
    }

    @Test
    void successfulBodyIsReturnedUnchanged() {
        assertEquals("ok", DownstreamCalls.execute("qr", () -> "ok"));
        assertEquals(Optional.of("ok"), DownstreamCalls.executeOptional("qr", () -> "ok"));
    }

    @Test
    void emptyBodyIsABadResponseNeverAnEmptyResult() {
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(() -> null));
        assertEquals(DependencyFailure.BAD_RESPONSE,
                assertThrows(DependencyException.class, () -> DownstreamCalls.executeOptional("qr", () -> null)).failure());
    }

    @Test
    void timeoutsAreRecognisedFromTheCauseChain() {
        assertEquals(DependencyFailure.TIMEOUT, failureOf(() -> {
            throw new ResourceAccessException("read", new HttpTimeoutException("request timed out"));
        }));
        assertEquals(DependencyFailure.TIMEOUT, failureOf(() -> {
            throw new ResourceAccessException("connect", new HttpConnectTimeoutException("connect timed out"));
        }));
        assertEquals(DependencyFailure.TIMEOUT, failureOf(() -> {
            throw new RestClientException("body", new SocketTimeoutException("Read timed out"));
        }));
    }

    @Test
    void networkFailuresAreUnavailable() {
        assertEquals(DependencyFailure.UNAVAILABLE, failureOf(() -> {
            throw new ResourceAccessException("refused", new ConnectException("Connection refused"));
        }));
        // A response body that breaks off after the headers surfaces as a plain RestClientException with an IO cause.
        assertEquals(DependencyFailure.UNAVAILABLE, failureOf(() -> {
            throw new RestClientException("body stream", new IOException("connection reset"));
        }));
    }

    @Test
    void unreadableOrUnexpectedBodiesAreBadResponses() {
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(() -> {
            throw new RestClientException("conversion", new HttpMessageNotReadableException("not json", null, null));
        }));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(() -> {
            throw new RestClientException("conversion", new IOException(new HttpMessageNotReadableException("bad", null, null)));
        }));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(() -> {
            throw new UnknownContentTypeException(String.class, MediaType.TEXT_PLAIN, 200, "OK",
                    HttpHeaders.EMPTY, new byte[0]);
        }));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(() -> {
            throw new RestClientException("something else");
        }));
    }

    @ParameterizedTest
    @CsvSource({"400,BAD_RESPONSE", "404,BAD_RESPONSE", "409,BAD_RESPONSE", "422,BAD_RESPONSE", "401,REJECTED",
            "403,REJECTED", "500,UNAVAILABLE", "502,UNAVAILABLE", "503,UNAVAILABLE", "504,UNAVAILABLE"})
    void statusCodesMapToDistinctFailures(int status, DependencyFailure expected) {
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        assertEquals(expected, failureOf(() -> {
            throw status < 500 ? HttpClientErrorException.create(httpStatus, "x", HttpHeaders.EMPTY, new byte[0], null)
                    : HttpServerErrorException.create(httpStatus, "x", HttpHeaders.EMPTY, new byte[0], null);
        }));
    }

    @Test
    void notFoundIsAnAnswerOnlyForLookups() {
        Supplier<String> notFound = () -> {
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", HttpHeaders.EMPTY, new byte[0], null);
        };
        assertEquals(Optional.empty(), DownstreamCalls.executeOptional("qr", notFound));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOf(notFound));
        Supplier<String> unavailable = () -> {
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "x", HttpHeaders.EMPTY, new byte[0], null);
        };
        assertEquals(DependencyFailure.UNAVAILABLE,
                assertThrows(DependencyException.class, () -> DownstreamCalls.executeOptional("qr", unavailable)).failure());
    }

    @Test
    void failureMessagesNameTheLogicalDependencyOnly() {
        DependencyException error = assertThrows(DependencyException.class, () -> DownstreamCalls.execute("order", () -> {
            throw new ResourceAccessException("I/O error on GET request for \"http://10.0.0.5:8080/secret\"",
                    new ConnectException("refused"));
        }));
        assertEquals("order", error.dependency());
        assertTrue(!error.getMessage().contains("10.0.0.5") && !error.getMessage().contains("secret"));
    }
}
