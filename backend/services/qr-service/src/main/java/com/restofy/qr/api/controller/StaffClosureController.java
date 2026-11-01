package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.StaffCloseRequest;
import com.restofy.qr.api.dto.response.StaffClosureResponse;
import com.restofy.qr.application.service.StaffClosureService;
import com.restofy.qr.domain.StaffClosure.Origin;
import com.restofy.qr.infrastructure.security.SessionLifecycleAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/table-sessions/{sessionId}")
@Tag(name = "Staff table closure", description = "Closed WAITER/ADMIN boundary. Staff requests do not bypass Order/Payment.")
public class StaffClosureController {
    private final StaffClosureService service;
    private final SessionLifecycleAccess access;
    public StaffClosureController(StaffClosureService service, SessionLifecycleAccess access) {
        this.service = service; this.access = access;
    }

    @PostMapping("/close")
    @Operation(summary = "Request an Order-authorised closure as verified staff",
            description = "Requires verified same-restaurant WAITER/ADMIN, stable commandId equal to Idempotency-Key, "
                    + "target main version and trusted user context. 200 CLOSED or 202 CLOSING; no payment claim or force flag. HTTP remains closed.")
    public ResponseEntity<StaffClosureResponse> close(@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody StaffCloseRequest request) {
        return StaffClosureHttp.accepted(service.close(StaffClosureHttp.command(sessionId, null, key, request), Origin.STAFF));
    }

    @GetMapping("/close-commands/{commandId}")
    @Operation(summary = "Read a staff closure result without replaying the command")
    public ResponseEntity<StaffClosureResponse> status(@PathVariable UUID sessionId, @PathVariable UUID commandId,
                                                       @RequestParam UUID tableId) {
        UUID restaurantId = access.requireStaff().restaurantId();
        return StaffClosureHttp.status(service.status(restaurantId, tableId, sessionId, commandId, Origin.STAFF));
    }
}
