package com.restofy.waiter.infrastructure.client.order;

/**
 * Order answered a command with a definite refusal (the command was not applied). The code is one of the
 * known reasons or {@link #GENERIC}; text sent by Order is never passed on.
 */
public class OrderRejectedException extends RuntimeException {

    public static final String NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String GENERIC = "ORDER_REJECTED";

    private final String code;

    public OrderRejectedException(String code) {
        super("Order refused the command: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
