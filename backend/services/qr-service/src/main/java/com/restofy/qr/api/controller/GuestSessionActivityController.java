package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.GuestActivityRequest;
import com.restofy.qr.api.dto.response.GuestActivityResponse;
import com.restofy.qr.application.service.GuestSessionActivityService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/guest-sessions")
public class GuestSessionActivityController {
    private final GuestSessionActivityService service;

    public GuestSessionActivityController(GuestSessionActivityService service) {
        this.service = service;
    }

    @PostMapping("/activity")
    @Operation(summary = "Record explicit foreground activity for a still-usable guest",
            description = "HTTP stays closed pending Auth/browser integration. Valid guest credential and matching context " +
                    "required. Renews only this guest and its main; reads/polling do not renew. Admission and verified " +
                    "business/financial outcomes can protect the owner and main; response flags make that explicit. " +
                    "Cancellation restart requires verified safe coverage; late financial reconciliation never revives " +
                    "an unusable released guest. This client signal is not proof of an order, payment, physical presence " +
                    "or a human gesture.")
    public ResponseEntity<GuestActivityResponse> recordActivity(@Valid @RequestBody GuestActivityRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recordActivity(request));
    }
}
