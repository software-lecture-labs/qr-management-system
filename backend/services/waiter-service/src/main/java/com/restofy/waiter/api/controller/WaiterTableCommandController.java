package com.restofy.waiter.api.controller;

import com.restofy.waiter.api.dto.request.CleanTableRequest;
import com.restofy.waiter.api.dto.request.CloseTableRequest;
import com.restofy.waiter.api.dto.response.TableCommandResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.application.service.TableCommandService;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Table closure and cleaning of the waiter panel. Protected: WAITER role of the caller's restaurant. Both
 * commands are forwarded to QR, which alone decides and records them; whether a session may close is assessed by
 * Order for QR (ADR-QR-001 K7), never by Waiter. The restaurant and the acting staff member always come from the
 * verified identity, never from the request.
 */
@RestController
@RequestMapping("/api/v1/waiters/tables")
public class WaiterTableCommandController {

    private final TableCommandService commands;

    public WaiterTableCommandController(TableCommandService commands) {
        this.commands = commands;
    }

    /**
     * Asks QR to close the named main session. 200 means QR confirmed that the main session and all its guest
     * sessions ended; 202 means QR accepted the close but has not finished (not a success).
     */
    @PostMapping("/{tableId}/sessions/{tableSessionId}/close")
    public ResponseEntity<TableCommandResponse> close(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID tableId,
            @PathVariable UUID tableSessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CloseTableRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        return respond(commands.close(staff, tableId, tableSessionId, body.expectedVersion(), body.reasonText(),
                idempotencyKey));
    }

    /** Asks QR to make the cleaned table available; the table must be cleaning and without a live session. */
    @PostMapping("/{tableId}/cleaned")
    public ResponseEntity<TableCommandResponse> cleaned(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CleanTableRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        return respond(commands.clean(staff, tableId, body.expectedVersion(), idempotencyKey));
    }

    /** Outcome of an earlier command; asks QR about commands without a final answer. */
    @GetMapping("/{tableId}/commands/{commandId}")
    public TableCommandResponse command(@AuthenticationPrincipal AuthenticatedStaff staff, @PathVariable UUID tableId,
                                        @PathVariable UUID commandId, HttpServletRequest request) {
        QueryParameters.allow(request);
        return WaiterMapper.toTableCommand(commands.status(staff, tableId, commandId));
    }

    private static ResponseEntity<TableCommandResponse> respond(TableCommandService.Result result) {
        HttpStatus status = result.command().getStatus() == TableCommandStatus.IN_PROGRESS
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WaiterMapper.toTableCommand(result));
    }
}
