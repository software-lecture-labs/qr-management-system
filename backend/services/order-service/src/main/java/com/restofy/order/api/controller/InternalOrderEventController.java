package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.KitchenCancellationDecisionRequest;
import com.restofy.order.api.dto.request.KitchenCancellationRequest;
import com.restofy.order.api.dto.request.KitchenOrderEventRequest;
import com.restofy.order.api.dto.request.PaymentOrderEventRequest;
import com.restofy.order.api.dto.response.CancellationDecisionResponse;
import com.restofy.order.api.dto.response.KitchenCancellationRequestResponse;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.CancellationDecisionService;
import com.restofy.order.application.service.KitchenCancellationRequestService;
import com.restofy.order.application.service.OrderFulfillmentService;
import com.restofy.order.application.service.OrderPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. They are not routed by the API Gateway and only a verified calling service may use them
 * ({@code Authorization: Service ...}); a guest token or a staff credential is refused.
 */
@RestController
@RequestMapping("/api/v1/internal/order-events")
@Tag(name = "Internal", description = "Events from other services (service credential only, not exposed by the gateway)")
@SecurityRequirement(name = "service")
public class InternalOrderEventController {

    private final OrderFulfillmentService fulfillment;
    private final OrderPaymentService payments;
    private final CancellationDecisionService cancellationDecisions;
    private final KitchenCancellationRequestService kitchenRequests;

    public InternalOrderEventController(OrderFulfillmentService fulfillment, OrderPaymentService payments,
                                        CancellationDecisionService cancellationDecisions,
                                        KitchenCancellationRequestService kitchenRequests) {
        this.fulfillment = fulfillment;
        this.payments = payments;
        this.cancellationDecisions = cancellationDecisions;
        this.kitchenRequests = kitchenRequests;
    }

    @PostMapping("/kitchen-cancellation-request")
    @Operation(summary = "Kitchen asks to cancel an order (or lines of it) it cannot make",
            description = "The Kitchen's OWN request - not an answer to a request of the Order Service (that is "
                    + "/kitchen-cancellation). Receiving it never cancels by itself: the Order Service applies its rules "
                    + "and either cancels (outcome APPLIED) or refuses and records the refusal (outcome REJECTED with "
                    + "rejectionCode ORDER_ALREADY_PREPARED for READY and later, ORDER_ALREADY_PAID for a paid order, "
                    + "which a waiter or admin decides). A reason is required: reasonCodes (at least one; reasonText too "
                    + "when OTHER is used). Either cancelAll=true or items; requestedByStaffId optionally names the "
                    + "kitchen staff member for the audit trail. The event id makes redelivery safe (duplicate=true, "
                    + "nothing is done twice; the same id with another body is 409). 409 CANCELLATION_ALREADY_PENDING "
                    + "while another request awaits the Kitchen's answer (answer that one), 409 ORDER_ALREADY_CANCELLED "
                    + "when nothing is left to cancel, 422 for an invalid selection or missing reason. An order of "
                    + "another restaurant than the one named is 404.")
    @ApiResponse(responseCode = "200", description = "Handled: APPLIED or REJECTED (recorded)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Kitchen service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "CANCELLATION_ALREADY_PENDING, ORDER_ALREADY_CANCELLED or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR, UNKNOWN_ORDER_ITEM or CANCEL_QUANTITY_EXCEEDED")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public KitchenCancellationRequestResponse kitchenCancellationRequest(@AuthenticationPrincipal ServiceActor service,
                                                                         @Valid @RequestBody KitchenCancellationRequest event) {
        return kitchenRequests.request(service, event);
    }

    @PostMapping("/kitchen-cancellation")
    @Operation(summary = "Kitchen answers a cancellation request that waited for it (APPROVED or REJECTED)",
            description = "Only the Kitchen service finalises a cancellation of an order in preparation. APPROVED is "
                    + "checked again against the order as it is now: if the order was paid in the meantime (customer "
                    + "request), is READY or beyond, or changed, the Order Service rejects the request itself "
                    + "(cancellationStatus REJECTED, decision source SYSTEM) instead of cancelling. REJECTED needs a "
                    + "rejectionCode. The event id makes redelivery safe (duplicate=true returns the original outcome); "
                    + "a cancellation that was already decided is IGNORED_STALE. An order of another restaurant than the "
                    + "one named, or a cancellation of another order, is 404.")
    @ApiResponse(responseCode = "200", description = "Acknowledged (APPLIED or IGNORED_STALE)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Kitchen service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND or CANCELLATION_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public CancellationDecisionResponse kitchenCancellation(@AuthenticationPrincipal ServiceActor service,
                                                            @Valid @RequestBody KitchenCancellationDecisionRequest event) {
        return cancellationDecisions.decide(service, event);
    }

    @PostMapping("/kitchen")
    @Operation(summary = "Kitchen reports the preparation status of an order (WAITING, PREPARING, READY)",
            description = "Kitchen V1 maps to the order lifecycle: WAITING = RECEIVED, PREPARING, READY. The event id "
                    + "makes redelivery safe: a repeated id returns the original outcome with duplicate=true. A late or "
                    + "repeated status is acknowledged as IGNORED_STALE, an event for a cancelled order as "
                    + "IGNORED_ORDER_CLOSED. A status that would skip a step is 409 INVALID_STATUS_TRANSITION and is "
                    + "not remembered, so the sender can retry it after the missing step. An order of another "
                    + "restaurant than the one named is 404.")
    @ApiResponse(responseCode = "200", description = "Acknowledged (APPLIED, IGNORED_STALE or IGNORED_ORDER_CLOSED)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Kitchen service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public OrderEventResponse kitchen(@AuthenticationPrincipal ServiceActor service,
                                      @Valid @RequestBody KitchenOrderEventRequest event) {
        return fulfillment.applyKitchenEvent(service, event);
    }

    @PostMapping("/payment")
    @Operation(summary = "Payment reports the payment status of an order (PENDING, PAID, FAILED, CANCELLED)",
            description = "The only way an order's payment status changes; it never affects the fulfillment status. "
                    + "PENDING and PAID must carry the order's current due amount and currency (409 PAYMENT_MISMATCH "
                    + "otherwise). The event id makes redelivery safe (duplicate=true returns the original outcome); "
                    + "an event older than the last applied one, a repeated status or a PENDING after the payment "
                    + "ended is IGNORED_STALE; events for a cancelled order are IGNORED_ORDER_CLOSED, except PAID, which "
                    + "is 409 because the money needs a manual refund decision. A move that is not legal (for example "
                    + "PAID before PENDING) is 409 INVALID_STATUS_TRANSITION and is not remembered, so the sender can "
                    + "retry after the missing step. An order of another restaurant than the one named is 404.")
    @ApiResponse(responseCode = "200", description = "Acknowledged (APPLIED, IGNORED_STALE or IGNORED_ORDER_CLOSED)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Payment service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION, PAYMENT_MISMATCH or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public OrderEventResponse payment(@AuthenticationPrincipal ServiceActor service,
                                      @Valid @RequestBody PaymentOrderEventRequest event) {
        return payments.applyPaymentEvent(service, event);
    }
}
