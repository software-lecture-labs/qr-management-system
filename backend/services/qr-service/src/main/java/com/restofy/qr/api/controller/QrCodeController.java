package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.dto.response.QrCodeResponse;
import com.restofy.qr.api.dto.request.QrCodeVersionRequest;
import com.restofy.qr.api.dto.request.ChangeQrCodeActiveRequest;
import com.restofy.qr.application.service.QrCodeCreationService;
import com.restofy.qr.application.service.QrCodeLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/tables/{tableId}/qr-codes")
@Tag(name = "QR management", description = "Protected APIs. Fail closed until verified Auth/tenant integration is available.")
public class QrCodeController {
    private final QrCodeCreationService service;
    private final QrCodeLifecycleService lifecycle;

    public QrCodeController(QrCodeCreationService service, QrCodeLifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }

    @PostMapping
    @Operation(summary = "Create one QR credential for an active table in the verified restaurant",
            description = "No request body. The server generates the QR token. Protected image and download endpoints can redisplay it.")
    public ResponseEntity<CreatedQrCodeResponse> create(@PathVariable UUID tableId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(tableId));
    }

    @PostMapping("/{qrId}/rotate")
    @Operation(summary = "Replace an active QR credential using its current version",
            description = "Revokes the previous credential and issues a new one atomically. Guest session policy is pending.")
    public ResponseEntity<CreatedQrCodeResponse> rotate(@PathVariable UUID tableId, @PathVariable UUID qrId,
                                                       @Valid @RequestBody QrCodeVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.rotate(tableId, qrId, request));
    }

    @PatchMapping("/{qrId}/active")
    @Operation(summary = "Enable or disable a non-revoked QR using its current version")
    public ResponseEntity<QrCodeResponse> changeActive(@PathVariable UUID tableId, @PathVariable UUID qrId,
                                                      @Valid @RequestBody ChangeQrCodeActiveRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lifecycle.changeActive(tableId, qrId, request));
    }

    @DeleteMapping("/{qrId}")
    @Operation(summary = "Permanently delete a QR using its current version query parameter",
            description = "Referencing records must block deletion. No cascade or soft-delete substitution.")
    public ResponseEntity<Void> delete(@PathVariable UUID tableId, @PathVariable UUID qrId,
                                       @Valid @ModelAttribute QrCodeVersionRequest request) {
        lifecycle.delete(tableId, qrId, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
