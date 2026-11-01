package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One page of the cancellation report of a restaurant plus a summary of EVERYTHING the filter matches (not only the
 * page). Amounts are minor units. A cancellation that is still REQUESTED shows projected amounts and a REJECTED one
 * cancelled nothing; {@code summary.totals} therefore count APPLIED cancellations only. A REQUESTED one also shows how
 * long it has waited ({@code pendingAgeSeconds}) and whether that is {@code overdue}. {@code requestedBy.id} is the
 * staff user id for staff requests (this report is ADMIN only) and never a guest identifier.
 */
public record CancellationReportResponse(List<Item> items, int page, int limit, long totalItems, int totalPages,
                                         Summary summary) {

    public CancellationReportResponse {
        items = ResponseCollections.copyOf(items);
    }

    public record Item(UUID cancellationId, UUID orderId, String orderNo, UUID tableId, String scope, String status,
                       boolean kitchenConfirmationRequired, String decisionSource, CancellationResponse.Actor requestedBy,
                       Instant requestedAt, Instant decidedAt, String rejectionCode, long unitsInRequest,
                       List<Reason> reasons, boolean reasonPending, CancellationResponse.Amounts amounts,
                       Long pendingAgeSeconds, boolean overdue) {

        public Item {
            reasons = ResponseCollections.copyOf(reasons);
        }
    }

    public record Reason(String source, String code, String text) {
    }

    /** {@code byReason} counts cancellations (not reasons) per catalog code; a cancellation may have several. */
    public record Summary(long totalCount, Map<String, Long> byStatus, List<Totals> totals, List<ReasonCount> byReason,
                          long withoutReasonCount, Pending pending) {

        public Summary {
            byStatus = ResponseCollections.copyOf(byStatus);
            totals = ResponseCollections.copyOf(totals);
            byReason = ResponseCollections.copyOf(byReason);
        }
    }

    /**
     * The requests still waiting for the Kitchen's answer within the filter. {@code overdueCount} are those waiting longer
     * than {@code attentionAfterSeconds} (restofy.cancellation.pending-attention-after). Visibility only: nothing is
     * decided, sent or escalated because time passed.
     */
    public record Pending(long attentionAfterSeconds, long overdueCount, Instant oldestRequestedAt) {
    }

    public record Totals(String currency, long appliedCount, long cancelledGrossMinor, long refundRequiredMinor,
                         long refundedMinor) {
    }

    public record ReasonCount(String code, String group, long count) {
    }
}
