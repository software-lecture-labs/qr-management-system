package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.ValidateQrCodeRequest;
import com.restofy.qr.api.dto.response.ValidatedQrCodeResponse;
import com.restofy.qr.application.service.QrCodeValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qr/validate")
@Tag(name = "QR validation", description = "HTTP access stays closed until reviewed scan/Auth integration. No session is created.")
public class QrValidationController {
    private final QrCodeValidationService service;

    public QrValidationController(QrCodeValidationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Resolve an active QR credential to its server-owned restaurant and table",
            description = "Body contains only qrToken. Returns a lookup snapshot, never staff/guest authorization. " +
                    "Invalid/unavailable QR: 404. HTTP remains fail closed.")
    public ResponseEntity<ValidatedQrCodeResponse> validate(@Valid @RequestBody ValidateQrCodeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.validate(request.qrToken()));
    }
}
