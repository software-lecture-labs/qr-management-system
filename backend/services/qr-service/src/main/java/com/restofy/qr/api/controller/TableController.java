package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/tables")
@Tag(name = "Table management", description = "Protected APIs. Fail closed until verified Auth/tenant integration is available.")
public class TableController {
    private final TableManagementService service;

    public TableController(TableManagementService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a table in the verified restaurant")
    public ResponseEntity<TableResponse> create(@Valid @RequestBody CreateTableRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/qr/tables/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace table details using the current version")
    public ResponseEntity<TableResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateTableRequest request) {
        return response(service.update(id, request));
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Enable or disable an idle table using the current version")
    public ResponseEntity<TableResponse> changeActive(@PathVariable UUID id, @Valid @RequestBody ChangeTableActiveRequest request) {
        return response(service.changeActive(id, request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a table in the verified restaurant")
    public ResponseEntity<TableResponse> get(@PathVariable UUID id) {
        return response(service.get(id));
    }

    @GetMapping
    @Operation(summary = "List restaurant tables with independent active and operational status filters")
    public ResponseEntity<TablePageResponse> list(@RequestParam(required = false) String isActive,
            @RequestParam(required = false) String operationalStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "tableNumber") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        Boolean active = switch (isActive) {
            case null -> null;
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("isActive must be true or false.");
        };
        TableOperationalStatus status = operationalStatus == null ? null : TableOperationalStatus.valueOf(operationalStatus);
        return response(service.list(active, status, page, size, sort, direction));
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
