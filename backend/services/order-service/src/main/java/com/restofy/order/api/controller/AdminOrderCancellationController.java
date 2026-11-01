package com.restofy.order.api.controller;

import com.restofy.order.api.dto.response.CancellationReportResponse;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.application.service.CancellationReportQuery;
import com.restofy.order.application.service.CancellationReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reporting for the restaurant's administrator. Data only: there is no admin screen in this service. */
@RestController
@RequestMapping("/api/v1/admin/order-cancellations")
@Tag(name = "Admin", description = "Cancellation report for the restaurant administrator (ADMIN staff only)")
public class AdminOrderCancellationController {

    private final CancellationReportService reports;

    public AdminOrderCancellationController(CancellationReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Cancellation report of the administrator's restaurant, paged, with a summary",
            description = "ADMIN only (403 for every other role, a guest and a service). Always the caller's own "
                    + "restaurant. Filters: from (inclusive) and to (exclusive) on the time of the request, as an ISO date "
                    + "or UTC instant; reasonCode (catalog code: the cancellation has that reason); scope FULL or "
                    + "PARTIAL; status REQUESTED, APPLIED or REJECTED; overdue=true only the requests that "
                    + "have waited for the Kitchen's answer longer than restofy.cancellation.pending-attention-after "
                    + "(default 15 minutes; visibility only, nothing is decided or sent because time passed). Newest "
                    + "first by default, sort=requestedAt,asc lists the longest-waiting first; page is 0-based, limit "
                    + "1-100 (default 20). summary describes everything the filter matches, not only the page: counts per "
                    + "status, the money of the APPLIED cancellations per currency (cancelled, refund REQUIRED and refund "
                    + "that really HAPPENED, kept apart), how many cancellations each reason code appears in, and how "
                    + "many have no reason yet. A REQUESTED cancellation shows projected amounts, a REJECTED one "
                    + "cancelled nothing. A staff requester's user id is shown; a guest identifier never is.")
    @SecurityRequirement(name = "staff")
    @ApiResponse(responseCode = "200", description = "The report page and its summary")
    @ApiResponse(responseCode = "401", description = "Missing or invalid staff credential")
    @ApiResponse(responseCode = "403", description = "Not an administrator")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR (bad date, code, status or paging)")
    public CancellationReportResponse report(@AuthenticationPrincipal StaffActor admin,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String reasonCode,
                                             @RequestParam(required = false) String scope,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Boolean overdue,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer limit) {
        return reports.report(admin, CancellationReportQuery.of(from, to, reasonCode, scope, status, overdue, sort, page, limit));
    }
}
