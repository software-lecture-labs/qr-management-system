package com.restofy.qr.api.controller;

import com.restofy.qr.domain.SessionClosure.Result;

import com.restofy.qr.api.dto.request.SessionLifecycleRequest;
import com.restofy.qr.api.dto.response.SessionLifecycleResponse;
import com.restofy.qr.application.service.TableSessionClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/table-sessions/{sessionId}")
@Tag(name = "Table cleaning", description = "Closed staff boundary for the separate cleaned action. Automatic and staff-requested closure use Order authority.")
public class TableSessionLifecycleController {
    private final TableSessionClosureService service;
    public TableSessionLifecycleController(TableSessionClosureService service) { this.service = service; }

    @PostMapping("/cleaned")
    @Operation(summary = "Mark a closed session's table cleaned and available",
            description = "Body version is the current TABLE version. Verified same-restaurant WAITER/ADMIN approval, CLOSED " +
                    "main and no later/live session are required. CLEANING becomes AVAILABLE; an already available " +
                    "target is a no-op. Stale mutation returns 409. This does not reopen old guests or alter isActive. " +
                    "HTTP remains closed.")
    public ResponseEntity<SessionLifecycleResponse> cleaned(@PathVariable UUID sessionId,
                                                            @Valid @RequestBody SessionLifecycleRequest request) {
        return response(service.cleaned(sessionId, request.version()));
    }

    private static ResponseEntity<SessionLifecycleResponse> response(Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SessionLifecycleResponse.from(result));
    }
}
