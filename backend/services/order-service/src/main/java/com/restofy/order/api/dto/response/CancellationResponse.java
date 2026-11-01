package com.restofy.order.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One cancellation of an order, as its readers may see it. Asking and finalising are separate: {@code status} is
 * REQUESTED while it waits for the kitchen's confirmation, APPLIED once the order really changed, REJECTED if it did
 * not. The amounts keep apart what the order was worth, what was cancelled, what it is worth now, what was verifiably
 * collected, what is still owed, and the refund that is REQUIRED versus the refund that actually HAPPENED (which only the
 * Payment Service can confirm and which the Order Service never records itself).
 *
 * <p>An author that is a SERVICE (the Kitchen) is shown as {@code type=SERVICE}, {@code role=KITCHEN}; ADMIN also sees its
 * verified identity in {@code id} and, apart from it, the staff member the service CLAIMED to act for in {@code
 * reportedStaffId} (a claim of the service, never a verified staff action).
 *
 * <p>{@code reasonPending} is true while neither the customer nor a waiter has given a reason. Staff identifiers are
 * visible to ADMIN only.
 */
public record CancellationResponse(UUID cancellationId, UUID orderId, String orderNo, String scope, String status,
                                   boolean kitchenConfirmationRequired, String decisionSource, List<Item> items,
                                   List<Reason> reasons, boolean reasonPending, Amounts amounts, Instant requestedAt,
                                   Instant decidedAt, Actor requestedBy, Actor decidedBy, Rejection rejection,
                                   OrderState order) {

    public CancellationResponse {
        items = ResponseCollections.copyOf(items);
        reasons = ResponseCollections.copyOf(reasons);
    }

    public record Item(UUID orderItemId, UUID productId, String name, int quantity, long unitGrossMinor,
                       long grossMinor, long netMinor, long vatMinor) {
    }

    public record Reason(String source, String code, String text, Actor addedBy, Instant addedAt) {
    }

    public record Amounts(String currency, long previousDueMinor, long cancelledGrossMinor, long cancelledNetMinor,
                          long cancelledVatMinor, long newDueMinor, long collectedMinor, long refundRequiredMinor,
                          long refundedMinor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Actor(String type, String role, String id, String reportedStaffId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rejection(String code, String note) {
    }

    /** The order's two independent statuses right now. */
    public record OrderState(String fulfillmentStatus, String paymentStatus) {
    }
}
