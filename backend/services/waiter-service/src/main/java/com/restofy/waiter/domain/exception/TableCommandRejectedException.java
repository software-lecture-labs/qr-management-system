package com.restofy.waiter.domain.exception;

import java.util.List;
import java.util.UUID;

/**
 * QR (or the trust boundary towards it) definitively did not apply the table command. {@code status} is the
 * HTTP status Waiter answers with; {@code code} is a stable machine-readable reason; {@code blockers} are the
 * known closure blockers QR named, if any. Nothing was closed or cleaned.
 */
public class TableCommandRejectedException extends RuntimeException {

    private final int status;
    private final String code;
    private final UUID commandId;
    private final List<String> blockers;

    public TableCommandRejectedException(int status, String code, UUID commandId, List<String> blockers, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.commandId = commandId;
        this.blockers = List.copyOf(blockers);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public UUID commandId() {
        return commandId;
    }

    public List<String> blockers() {
        return blockers;
    }
}
