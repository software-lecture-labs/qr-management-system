package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.TableSessionStatisticsResponse;
import com.restofy.qr.application.service.TableSessionStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/tables")
@Tag(name = "Table session statistics", description = "Management scope; HTTP stays fail closed pending Auth integration.")
public class TableSessionStatisticsController {
    private final TableSessionStatisticsService service;

    public TableSessionStatisticsController(TableSessionStatisticsService service) {
        this.service = service;
    }

    @GetMapping("/{id}/session-statistics")
    @Operation(summary = "Get live guest and recorded accepted-scan counts for a verified restaurant table",
            description = "Counts are not unique physical people. Active guests must be usable with an OPEN main and active " +
                    "OCCUPIED table. Scan totals cover recorded accepted events since V28, not unrecorded earlier " +
                    "history. Rejected requests are excluded. No guest credentials are exposed; HTTP remains closed.")
    public ResponseEntity<TableSessionStatisticsResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
}
