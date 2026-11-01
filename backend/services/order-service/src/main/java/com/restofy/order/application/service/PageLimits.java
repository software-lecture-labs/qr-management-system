package com.restofy.order.application.service;

/** The one paging rule of every list endpoint: how many rows a page has by default and at most. */
final class PageLimits {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private PageLimits() {
    }
}
