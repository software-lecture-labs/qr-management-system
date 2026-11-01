package com.restofy.waiter.infrastructure.client;

import java.util.List;

/**
 * Contract checks for downstream DTO constructors. A violation surfaces as a deserialization failure,
 * which the client adapters report as a bad downstream response instead of showing half-filled data.
 */
public final class Fields {

    private Fields() {
    }

    public static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static <T> List<T> requiredList(List<T> value, String name) {
        return List.copyOf(required(value, name));
    }
}
