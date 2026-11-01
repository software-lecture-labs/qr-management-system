package com.restofy.waiter.api.controller;

import com.restofy.waiter.api.dto.request.ServeOrderRequest;
import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.api.dto.response.ServeCommandResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.application.service.OrderServeService;
import com.restofy.waiter.application.service.OrderFilter;
import com.restofy.waiter.application.service.OrderQueryService;
import com.restofy.waiter.application.service.PageParams;
import com.restofy.waiter.domain.enums.OrderSessionScope;
import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Order endpoints of the waiter panel. Protected: WAITER role of the caller's restaurant. Reads come from Order;
 * the single command, "served", is forwarded to Order, which alone decides and records it.
 */
@RestController
@RequestMapping("/api/v1/waiters/orders")
public class WaiterOrderController {

    private final OrderQueryService orders;
    private final OrderServeService serving;

    public WaiterOrderController(OrderQueryService orders, OrderServeService serving) {
        this.orders = orders;
        this.serving = serving;
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @RequestParam(required = false) OrderView view,
            @RequestParam(required = false) UUID tableId,
            @RequestParam(required = false) OrderSessionScope sessionScope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        QueryParameters.allow(request, "view", "tableId", "sessionScope", "page", "size");
        return orders.listOrders(staff, new OrderFilter(view, tableId, sessionScope), new PageParams(page, size));
    }

    /**
     * Marks a ready order as served by forwarding the command to Order. The restaurant and the acting staff
     * member come from the verified identity; the body only carries the context the staff member saw.
     */
    @PostMapping("/{orderId}/served")
    public ServeCommandResponse served(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ServeOrderRequest body,
            HttpServletRequest request) {
        QueryParameters.allow(request);
        return WaiterMapper.toServeCommand(serving.serve(staff, orderId, body.tableSessionId(), body.expectedVersion(),
                idempotencyKey));
    }

    /** Outcome of an earlier serve command; asks Order about commands whose outcome is still unknown. */
    @GetMapping("/{orderId}/served-commands/{commandId}")
    public ServeCommandResponse servedCommand(@AuthenticationPrincipal AuthenticatedStaff staff,
                                              @PathVariable UUID orderId, @PathVariable UUID commandId,
                                              HttpServletRequest request) {
        QueryParameters.allow(request);
        return WaiterMapper.toServeCommand(serving.status(staff, orderId, commandId));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthenticatedStaff staff, @PathVariable UUID orderId,
                             HttpServletRequest request) {
        QueryParameters.allow(request);
        return orders.getOrder(staff, orderId);
    }
}
