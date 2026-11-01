package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.QrCodeResponse;
import com.restofy.qr.api.dto.response.QrCodePageResponse;
import com.restofy.qr.application.service.QrCodeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/qr/tables/{tableId}/qr-codes")
@Tag(name = "QR management", description = "Protected APIs. Fail closed until verified Auth/tenant integration is available.")
public class QrCodeReadController {
    private final QrCodeQueryService queries;

    public QrCodeReadController(QrCodeQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "List QR metadata for a table in the verified restaurant",
            description = "Use after reload or a lost creation/rotation response. Newest first; page 0..10000, size 1..100. No " +
                    "tokens or storage payloads.")
    public ResponseEntity<QrCodePageResponse> list(@PathVariable UUID tableId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(tableId, page, size));
    }

    @GetMapping("/{qrId}")
    @Operation(summary = "Read QR metadata and its current version in the verified restaurant",
            description = "Returns lifecycle metadata only; never returns the token, digest or encrypted payload.")
    public ResponseEntity<QrCodeResponse> detail(@PathVariable UUID tableId, @PathVariable UUID qrId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(tableId, qrId));
    }

    @GetMapping(value = "/{qrId}/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "View a non-revoked QR as a fixed-size PNG in the verified restaurant",
            description = "Inactive QR/table credentials can be printed but do not gain scan access. No mutation. URL not configured: 503.")
    public ResponseEntity<byte[]> image(@PathVariable UUID tableId, @PathVariable UUID qrId) {
        return png(tableId, qrId, false);
    }

    @GetMapping(value = "/{qrId}/download", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Download the same non-revoked QR credential as PNG",
            description = "Same payload as image viewing, with attachment disposition. Revoked QR: 409; unknown or foreign QR: 404.")
    public ResponseEntity<byte[]> download(@PathVariable UUID tableId, @PathVariable UUID qrId) {
        return png(tableId, qrId, true);
    }

    private ResponseEntity<byte[]> png(UUID tableId, UUID qrId, boolean attachment) {
        byte[] bytes = queries.image(tableId, qrId);
        var disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename("qr-" + qrId + ".png").build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .contentLength(bytes.length).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("Referrer-Policy", "no-referrer").body(bytes);
    }
}
