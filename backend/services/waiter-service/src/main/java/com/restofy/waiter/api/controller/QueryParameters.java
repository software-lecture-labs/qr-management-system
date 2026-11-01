package com.restofy.waiter.api.controller;

import com.restofy.waiter.domain.exception.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Query allow-list. An unknown parameter is rejected instead of ignored so that a client can never believe
 * it selected a restaurant or role through the URL: the tenant only comes from the verified staff context.
 */
final class QueryParameters {

    /** Longest unknown parameter name that is echoed back in the error message. */
    private static final int MAX_ECHOED_NAME_LENGTH = 40;

    private QueryParameters() {
    }

    static void allow(HttpServletRequest request, String... allowed) {
        Set<String> known = Set.of(allowed);
        for (String name : request.getParameterMap().keySet()) {
            if (!known.contains(name)) {
                throw new InvalidRequestException("Unsupported query parameter: " + safe(name));
            }
        }
    }

    private static String safe(String name) {
        String trimmed = name.length() > MAX_ECHOED_NAME_LENGTH ? name.substring(0, MAX_ECHOED_NAME_LENGTH) : name;
        return trimmed.replaceAll("[^A-Za-z0-9_.\\-\\[\\]]", "?");
    }
}
