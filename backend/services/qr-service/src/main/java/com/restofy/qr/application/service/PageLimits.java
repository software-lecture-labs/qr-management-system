package com.restofy.qr.application.service;

final class PageLimits {
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SIZE = 100;

    private PageLimits() { }

    static void validate(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page must be 0.." + MAX_PAGE + " and size must be 1.." + MAX_SIZE + ".");
        }
    }
}
