package com.restofy.waiter.api.controller;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.api.dto.response.TableDetailResponse;
import com.restofy.waiter.api.dto.response.TableSummaryResponse;
import com.restofy.waiter.application.service.PageParams;
import com.restofy.waiter.application.service.TableFilter;
import com.restofy.waiter.application.service.TableQueryService;
import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only table endpoints of the waiter panel. Protected: WAITER role of the caller's restaurant. */
@RestController
@RequestMapping("/api/v1/waiters/tables")
public class WaiterTableController {

    private final TableQueryService tables;

    public WaiterTableController(TableQueryService tables) {
        this.tables = tables;
    }

    @GetMapping
    public PageResponse<TableSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @RequestParam(required = false) TableOperationalStatus status,
            @RequestParam(required = false) Boolean hasActiveOrder,
            @RequestParam(required = false) Boolean hasOpenCall,
            @RequestParam(required = false) Boolean billRequested,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        QueryParameters.allow(request, "status", "hasActiveOrder", "hasOpenCall", "billRequested", "page", "size");
        return tables.listTables(staff, new TableFilter(status, hasActiveOrder, hasOpenCall, billRequested),
                new PageParams(page, size));
    }

    @GetMapping("/{tableId}")
    public TableDetailResponse get(@AuthenticationPrincipal AuthenticatedStaff staff, @PathVariable UUID tableId,
                                   HttpServletRequest request) {
        QueryParameters.allow(request);
        return tables.getTable(staff, tableId);
    }

    @GetMapping("/{tableId}/orders")
    public PageResponse<OrderResponse> orders(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID tableId,
            @RequestParam(required = false) OrderView view,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        QueryParameters.allow(request, "view", "page", "size");
        return tables.listTableOrders(staff, tableId, view, new PageParams(page, size));
    }
}
