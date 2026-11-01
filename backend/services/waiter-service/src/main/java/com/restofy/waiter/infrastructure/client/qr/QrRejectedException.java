package com.restofy.waiter.infrastructure.client.qr;

import java.util.List;

/**
 * QR answered a table command with a definite refusal: the command was not applied. The code is one of the
 * known reasons or {@link #GENERIC}; the blockers are the known blocker codes. Text sent by QR is never passed on.
 */
public class QrRejectedException extends RuntimeException {

    public static final String GENERIC = "QR_REJECTED";

    private final int status;
    private final String code;
    private final List<String> blockers;

    public QrRejectedException(int status, String code, List<String> blockers) {
        super("QR refused the command: " + code);
        this.status = status;
        this.code = code;
        this.blockers = List.copyOf(blockers);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> blockers() {
        return blockers;
    }
}
