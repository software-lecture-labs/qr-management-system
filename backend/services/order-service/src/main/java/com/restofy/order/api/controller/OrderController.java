package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.CancelOrderRequest;
import com.restofy.order.api.dto.request.CancellationReasonRequest;
import com.restofy.order.api.dto.request.CheckoutRequest;
import com.restofy.order.api.dto.response.CancellationListResponse;
import com.restofy.order.api.dto.response.CancellationResponse;
import com.restofy.order.api.dto.request.FulfillmentStatusRequest;
import com.restofy.order.api.dto.response.FulfillmentStatusResponse;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.application.service.OrderFulfillmentService;
import com.restofy.order.api.dto.response.OrderDetailResponse;
import com.restofy.order.api.dto.response.OrderHistoryResponse;
import com.restofy.order.api.dto.response.OrderListResponse;
import com.restofy.order.api.dto.response.OrderResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.service.OrderCancellationService;
import com.restofy.order.application.service.OrderCheckoutService;
import com.restofy.order.application.service.OrderListQuery;
import com.restofy.order.application.service.OrderQueryService;
import com.restofy.order.application.service.RequestActor;
import com.restofy.order.application.service.OrderCheckoutService.CheckoutResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Order placement for a QR guest. Reading and managing orders arrives in a later phase. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order", description = "Order placement, detail, status history and status changes")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final OrderCheckoutService checkout;
    private final OrderQueryService queries;
    private final OrderFulfillmentService fulfillment;
    private final OrderCancellationService cancellation;

    public OrderController(OrderCheckoutService checkout, OrderQueryService queries,
                           OrderFulfillmentService fulfillment, OrderCancellationService cancellation) {
        this.checkout = checkout;
        this.queries = queries;
        this.fulfillment = fulfillment;
        this.cancellation = cancellation;
    }

    @GetMapping
    @Operation(summary = "List orders of a table session or table (active, completed or cancelled), paged",
            description = "view filters on the FULFILLMENT status only: active = RECEIVED, PREPARING, READY, SERVED; "
                    + "completed = COMPLETED; cancelled = CANCELLED (default active). Payment is a separate axis: use "
                    + "paymentStatus to filter it. A guest always gets only their own orders of their own table "
                    + "session (a foreign tableSessionId/tableId is 403); staff (ADMIN, WAITER, KITCHEN) get their restaurant orders, "
                    + "optionally narrowed by tableSessionId (one visit) or tableId (the table across visits). "
                    + "KITCHEN gets a reduced view without payment status, amounts and prices and may not filter by "
                    + "paymentStatus (403). "
                    + "page is 0-based, limit 1-100 (default 20), sort placedAt,desc (default) or placedAt,asc.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderListResponse list(Authentication authentication,
                                  @RequestParam(required = false) UUID tableSessionId,
                                  @RequestParam(required = false) UUID tableId,
                                  @RequestParam(required = false) String view,
                                  @RequestParam(required = false) String paymentStatus,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String sort) {
        return queries.list(RequestActor.from(authentication),
                OrderListQuery.of(tableSessionId, tableId, view, paymentStatus, page, limit, sort));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Order detail with its immutable price snapshot and both independent statuses",
            description = "A guest sees only their own orders while their table session is active. Staff (ADMIN, "
                    + "WAITER) see the orders of their own restaurant; KITCHEN sees its own restaurant's orders in a "
                    + "reduced view (what to prepare, fulfillment status; no payment status, amounts or prices). An "
                    + "order that does not exist, belongs to another restaurant or to another guest is a 404.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderDetailResponse detail(Authentication authentication, @PathVariable UUID orderId) {
        return queries.detail(RequestActor.from(authentication), orderId);
    }

    @GetMapping("/{orderId}/history")
    @Operation(summary = "Append-only status timeline of an order (fulfillment and payment axes)",
            description = "Oldest first. Staff identifiers are visible to ADMIN only. KITCHEN may not read the history "
                    + "(403): it contains the payment axis and staff identifiers.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderHistoryResponse history(Authentication authentication, @PathVariable UUID orderId) {
        return queries.history(RequestActor.from(authentication), orderId);
    }

    @PatchMapping("/{orderId}/fulfillment-status")
    @Operation(summary = "Move an order one step along its fulfillment lifecycle (staff only)",
            description = "RECEIVED, PREPARING, READY, SERVED, COMPLETED, one step at a time. PREPARING and READY "
                    + "belong to the Kitchen: the Kitchen Service sends them as events, KITCHEN staff may set them here "
                    + "only in dev/test profiles (403 elsewhere). WAITER and ADMIN set SERVED and COMPLETED (an ADMIN "
                    + "action is recorded with its actor); any other role is 403. "
                    + "Skipping a step, going back or leaving COMPLETED/CANCELLED is 409 INVALID_STATUS_TRANSITION. "
                    + "CANCELLED cannot be set here (cancellation is a separate process). COMPLETED does not require "
                    + "payment: payment is an independent status. If the order is already in the requested status the "
                    + "call is a harmless no-op for any authorised caller (changed=false, no new history or event); a status "
                    + "behind the current one is 409. An order of another restaurant is 404. Guests cannot call this.")
    @SecurityRequirement(name = "staff")
    @ApiResponse(responseCode = "200", description = "Status changed (changed=true) or already in that status (changed=false)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid staff credential")
    @ApiResponse(responseCode = "403", description = "Not staff, or this role may not set that status")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND (also for another restaurant's order)")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR (unknown status, bad reason)")
    public FulfillmentStatusResponse changeFulfillmentStatus(@AuthenticationPrincipal StaffActor staff,
                                                             @PathVariable UUID orderId,
                                                             @Valid @RequestBody FulfillmentStatusRequest request) {
        return fulfillment.changeByStaff(staff, orderId, request.status(), request.reason());
    }

    @PostMapping
    @Operation(summary = "Turn the current cart into an order (idempotent per Idempotency-Key)",
            description = "The server re-prices the cart from the catalog and orders only if the client confirmed "
                    + "the same cart version and total. If any price differs from what the cart last validated (product, extra, VAT or "
                    + "catalog price version) the order is NOT placed unless the request carries both expectedPriceFingerprint (the "
                    + "priceFingerprint of the cart view the guest reviewed, bound to the cart version and every live price) and "
                    + "acknowledgePriceChanges=true: otherwise 409 PRICE_CHANGED with the changed components (details.priceChanges), "
                    + "details.currentPriceFingerprint and details.currentTotalMinor, and nothing is written (the cart, its version "
                    + "and the Idempotency-Key stay untouched). When no price changed neither field is needed. "
                    + "Repeating a finished request with the same key and body "
                    + "returns the same order (header Idempotent-Replayed: true). The order number (ORD-000001) is a "
                    + "display identifier only and never grants access.")
    @SecurityRequirement(name = "guestSession")
    @ApiResponse(responseCode = "201", description = "Order placed (or replayed for the same Idempotency-Key)")
    @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key, malformed body or "
                    + "unknown fields (prices and ids are never accepted from the client)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid guest session")
    @ApiResponse(responseCode = "409", description = "PRICE_CHANGED, CART_VERSION_CONFLICT, "
                    + "IDEMPOTENCY_CONFLICT, CART_ALREADY_CHECKED_OUT, PRODUCT_UNAVAILABLE, TAX_DATA_MISSING or "
                    + "TABLE_SESSION_NOT_ACTIVE")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR or CART_EMPTY")
    @ApiResponse(responseCode = "503", description = "QR or Menu service unavailable (fail closed)")
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal GuestSessionContext guest,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                    description = "8-120 characters of letters, digits, '.', '_', ':' or '-'. Scoped to the guest.")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutResult result = checkout.checkout(guest, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(result.order());
    }

    @PostMapping("/{orderId}/cancellations")
    @Operation(summary = "Cancel a whole order or chosen lines and unit counts (idempotent per Idempotency-Key)",
            description = "Send either cancelAll=true or items (orderItemId + quantity), never both. The reason is always "
                    + "optional: reasonCodes (CHANGED_MIND, ORDERED_BY_MISTAKE, WAIT_TOO_LONG, ITEM_UNAVAILABLE, "
                    + "KITCHEN_ERROR, OTHER; several allowed, none repeated) and/or a plain reasonText of at most 300 "
                    + "characters; without one a waiter can complete it later. A guest cancels only their own order while "
                    + "their table session is active; WAITER and ADMIN cancel any order of their restaurant; KITCHEN is 403. "
                    + "RECEIVED: decided at once (status APPLIED, decisionSource AUTO). PREPARING: stored as REQUESTED and "
                    + "finalised only by the Kitchen service (kitchenConfirmationRequired=true; the order does not change "
                    + "meanwhile). READY, SERVED, COMPLETED: 409 CANCELLATION_NOT_ALLOWED. A guest cannot cancel a paid "
                    + "order (409 CANCELLATION_NOT_ALLOWED, reason ORDER_ALREADY_PAID); staff can, and the refund it requires "
                    + "is recorded as refundRequiredMinor (a request, never a refund that happened). Cancelling every "
                    + "remaining unit makes the order CANCELLED. Repeating a finished request with the same key and body "
                    + "returns the same cancellation (header Idempotent-Replayed: true). An order of another restaurant or "
                    + "another guest is a 404.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    @ApiResponse(responseCode = "201", description = "Cancellation applied (APPLIED), waiting for the kitchen "
            + "(REQUESTED) or replayed for the same Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key or malformed body")
    @ApiResponse(responseCode = "401", description = "Missing or invalid guest session / staff credential")
    @ApiResponse(responseCode = "403", description = "KITCHEN or another role that may not cancel")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND (also for another restaurant's or guest's order)")
    @ApiResponse(responseCode = "409", description = "CANCELLATION_NOT_ALLOWED, CANCELLATION_ALREADY_PENDING, "
            + "ORDER_ALREADY_CANCELLED, IDEMPOTENCY_CONFLICT or TABLE_SESSION_NOT_ACTIVE")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR, UNKNOWN_ORDER_ITEM or CANCEL_QUANTITY_EXCEEDED")
    public ResponseEntity<CancellationResponse> cancel(
            Authentication authentication,
            @PathVariable UUID orderId,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                    description = "8-120 characters of letters, digits, '.', '_', ':' or '-'. Scoped to the caller.")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CancelOrderRequest request) {
        var outcome = cancellation.request(RequestActor.from(authentication), orderId, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(outcome.cancellation());
    }

    @GetMapping("/{orderId}/cancellations")
    @Operation(summary = "Every cancellation (request) of an order, oldest first",
            description = "A guest sees the cancellations of their own order; WAITER and ADMIN those of any order of "
                    + "their restaurant (staff identifiers for ADMIN only); KITCHEN is 403. reasonPending is true while "
                    + "neither the customer nor a waiter has given a reason. An order of another restaurant or guest is 404.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public CancellationListResponse cancellations(Authentication authentication, @PathVariable UUID orderId) {
        return cancellation.list(RequestActor.from(authentication), orderId);
    }

    @PatchMapping("/{orderId}/cancellations/{cancellationId}/reason")
    @Operation(summary = "A waiter or admin gives the reason of a cancellation that has none (append-only)",
            description = "WAITER or ADMIN of the restaurant (403 for KITCHEN, a guest and a service). Allowed once, and "
                    + "only while the cancellation has no reason - the customer's, a Kitchen's or a colleague's reason "
                    + "is never changed or added to (409 CANCELLATION_REASON_ALREADY_SET); "
                    + "repeating the very same completion by the same person returns the stored result. Needs reasonCodes "
                    + "and/or reasonText (same rules as the customer's). Works for a cancellation in any status. Who "
                    + "added it and when is recorded.")
    @SecurityRequirement(name = "staff")
    @ApiResponse(responseCode = "200", description = "Reason recorded (or the same completion repeated)")
    @ApiResponse(responseCode = "403", description = "Not a waiter")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND or CANCELLATION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "CANCELLATION_REASON_ALREADY_SET")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    public CancellationResponse completeReason(@AuthenticationPrincipal StaffActor staff, @PathVariable UUID orderId,
                                               @PathVariable UUID cancellationId,
                                               @Valid @RequestBody CancellationReasonRequest request) {
        return cancellation.completeReason(staff, orderId, cancellationId, request);
    }
}
