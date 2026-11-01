package com.restofy.order.api.dto.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive copies for the collections of response records. A record is only as immutable as its components: without a
 * copy, whoever built the list could still change a response that was already handed out (or shared with another
 * thread), and the response would expose its caller's list. The copy is shallow, which is enough because every element
 * is itself an immutable record of primitives, strings and instants.
 *
 * <p>A missing collection becomes an empty one, so the JSON always carries {@code []} (or an empty object) exactly as
 * before. Map iteration order (the order of the JSON fields) is preserved.
 */
final class ResponseCollections {

    private ResponseCollections() {
    }

    static <T> List<T> copyOf(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    static <K, V> Map<K, V> copyOf(Map<K, V> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
