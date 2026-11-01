package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.ValidateQrCodeRequest;
import com.restofy.qr.api.dto.response.OpenedGuestSessionResponse;
import com.restofy.qr.application.service.GuestSessionOpeningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/table-sessions")
@Tag(name = "Table sessions", description = "HTTP access remains fail closed. Browser/Auth integration is pending.")
public class TableSessionController {
    private final GuestSessionOpeningService service;

    public TableSessionController(GuestSessionOpeningService service) {
        this.service = service;
    }

    @PostMapping("/open")
    @Operation(summary = "Create a distinct guest in the table's new or existing main session",
            description = "Only qrToken is accepted. Every accepted scan creates a guest (201), including the first. Guest " +
                    "token is returned once; only its digest is stored. Scan renews the main idle deadline, not existing " +
                    "guests. Admission and verified business/financial outcomes determine main and owner idle " +
                    "protection; response orderProtected flags distinguish it from baseline idle timestamps. Complete " +
                    "safe cancellation coverage can restart idle windows. COMPLETED does not close the table; " +
                    "terminal/closing main is never revived. HTTP stays closed; real Order/Payment/Auth/browser " +
                    "integration is pending.")
    public ResponseEntity<OpenedGuestSessionResponse> open(@Valid @RequestBody ValidateQrCodeRequest request) {
        var result = service.open(request.qrToken());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }
}
