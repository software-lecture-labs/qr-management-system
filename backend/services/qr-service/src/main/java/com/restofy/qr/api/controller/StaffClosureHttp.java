package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.StaffCloseRequest;
import com.restofy.qr.api.dto.response.StaffClosureResponse;
import com.restofy.qr.api.dto.response.StaffClosureResponse.State;
import com.restofy.qr.domain.StaffClosure;
import com.restofy.qr.domain.exception.StaffClosureException;
import com.restofy.qr.domain.exception.StaffClosureException.Code;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class StaffClosureHttp {
    private StaffClosureHttp() { }
    static StaffClosure.Request command(UUID sessionId, UUID tableId, String key, StaffCloseRequest body) {
        if (!UUID.fromString(key).equals(body.commandId()) || !sessionId.equals(body.tableSessionId())
                || (tableId != null && !tableId.equals(body.tableId()))) {
            throw new IllegalArgumentException("Close target or idempotency key does not match.");
        }
        return body.command();
    }
    static ResponseEntity<StaffClosureResponse> accepted(StaffClosureResponse receipt) {
        if (receipt.state() == State.REJECTED) { throw new StaffClosureException(Code.CLOSURE_BLOCKED); }
        return ResponseEntity.status(receipt.state() == State.CLOSING ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(receipt);
    }
    static ResponseEntity<StaffClosureResponse> status(StaffClosureResponse receipt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);
    }
}
