package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.StaffCloseRequest;
import com.restofy.qr.api.dto.response.StaffClosureResponse;
import com.restofy.qr.application.service.StaffClosureService;
import com.restofy.qr.domain.StaffClosure.Origin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/tables/{tableId}/sessions/{sessionId}")
@Tag(name = "Waiter closure contract", description = "Closed service identity AND staff delegation boundary; not a Gateway public API.")
public class WaiterClosureController {
    private final StaffClosureService service;
    public WaiterClosureController(StaffClosureService service) { this.service = service; }

    @PostMapping("/close")
    @Operation(summary = "Request closure through verified Waiter service and delegated staff",
            description = "Waiter command/result fields are supported; /api/v1 internal prefix is canonical. Body IDs do not grant authority. "
                    + "Requires verified Waiter identity and active same-tenant WAITER/ADMIN delegation. HTTP remains denyAll.")
    public ResponseEntity<StaffClosureResponse> close(@PathVariable UUID tableId, @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody StaffCloseRequest request) {
        return StaffClosureHttp.accepted(service.close(StaffClosureHttp.command(sessionId, tableId, key, request), Origin.WAITER_SERVICE));
    }

    @GetMapping("/close-commands/{commandId}")
    @Operation(summary = "Read the durable closure status for Waiter reconciliation")
    public ResponseEntity<StaffClosureResponse> status(@PathVariable UUID tableId, @PathVariable UUID sessionId,
            @PathVariable UUID commandId, @RequestParam UUID restaurantId) {
        return StaffClosureHttp.status(service.status(restaurantId, tableId, sessionId, commandId, Origin.WAITER_SERVICE));
    }
}
