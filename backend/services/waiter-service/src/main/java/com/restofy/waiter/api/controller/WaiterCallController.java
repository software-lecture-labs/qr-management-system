package com.restofy.waiter.api.controller;

import com.restofy.waiter.api.dto.request.CallCommandRequest;
import com.restofy.waiter.api.dto.request.CreateCallRequest;
import com.restofy.waiter.api.dto.response.GuestCallResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.api.dto.response.ServiceCallResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.application.service.CallCommandService;
import com.restofy.waiter.application.service.CallFilter;
import com.restofy.waiter.application.service.CallQueryService;
import com.restofy.waiter.application.service.CallSort;
import com.restofy.waiter.application.service.PageParams;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.infrastructure.security.AuthenticatedGuest;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Waiter, help and bill calls. {@code POST} is the guest's endpoint (a verified QR guest session); everything
 * else is for waiters of the caller's restaurant. The restaurant, table and sessions of a new call come from
 * the verified guest context, and the acting staff member from the verified principal, never from the body,
 * the query or headers.
 */
@RestController
@RequestMapping("/api/v1/waiters/calls")
public class WaiterCallController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CallCommandService commands;
    private final CallQueryService queries;

    public WaiterCallController(CallCommandService commands, CallQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<GuestCallResponse> create(
            @AuthenticationPrincipal AuthenticatedGuest guest,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateCallRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        CallCommandService.Outcome outcome = commands.create(guest, body.type(), body.text(), idempotencyKey);
        return ResponseEntity.status(outcome.httpStatus()).body(WaiterMapper.toGuestCall(outcome.call()));
    }

    @GetMapping
    public PageResponse<ServiceCallResponse> list(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @RequestParam(required = false) List<ServiceRequestType> type,
            @RequestParam(required = false) List<ServiceRequestStatus> status,
            @RequestParam(required = false) UUID tableId,
            @RequestParam(required = false) UUID tableSessionId,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) CallSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        QueryParameters.allow(request, "type", "status", "tableId", "tableSessionId", "createdFrom", "createdTo",
                "sort", "page", "size");
        CallFilter filter = CallFilter.of(type, status, tableId, tableSessionId, createdFrom, createdTo, sort);
        return queries.list(staff, filter, new PageParams(page, size));
    }

    @GetMapping("/{callId}")
    public ServiceCallResponse get(@AuthenticationPrincipal AuthenticatedStaff staff, @PathVariable UUID callId,
                                   HttpServletRequest request) {
        QueryParameters.allow(request);
        return queries.get(staff, callId);
    }

    @PostMapping("/{callId}/claim")
    public ServiceCallResponse claim(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID callId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CallCommandRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        CallCommandService.Outcome outcome = commands.claim(staff, callId, body.expectedVersion(), idempotencyKey);
        return queries.present(staff.restaurantId(), outcome.call());
    }

    @PostMapping("/{callId}/resolve")
    public ServiceCallResponse resolve(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID callId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CallCommandRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        CallCommandService.Outcome outcome = commands.resolve(staff, callId, body.expectedVersion(), idempotencyKey);
        return queries.present(staff.restaurantId(), outcome.call());
    }
}
