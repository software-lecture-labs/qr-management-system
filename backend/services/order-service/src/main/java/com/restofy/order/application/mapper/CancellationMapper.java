package com.restofy.order.application.mapper;

import com.restofy.order.api.dto.response.CancellationResponse;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.ReasonRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds the cancellation response. A guest id is never returned; a staff user id is returned to an ADMIN only
 * ({@code revealStaffIds}), everyone else sees the role.
 */
@Component
public class CancellationMapper {

    private static final String STAFF = "STAFF";
    private static final String SERVICE = "SERVICE";

    public CancellationResponse toResponse(Order order, Row row, List<ItemRow> items, List<ReasonRow> reasons,
                                           boolean revealStaffIds) {
        var amounts = new CancellationResponse.Amounts(row.currency(), row.previousDueMinor(),
                row.cancelledGrossMinor(), row.cancelledNetMinor(), row.cancelledVatMinor(), row.newDueMinor(),
                row.collectedMinor(), row.refundRequiredMinor(), row.refundedMinor());
        var rejection = row.rejectionCode() == null ? null
                : new CancellationResponse.Rejection(row.rejectionCode(), row.rejectionNote());
        return new CancellationResponse(row.id(), order.getId(), order.getOrderNo(), row.scope(), row.status(),
                row.kitchenConfirmationRequired(), row.decisionSource(),
                items.stream().map(item -> new CancellationResponse.Item(item.orderItemId(), item.productId(),
                        item.productName(), item.quantity(), item.unitGrossMinor(), item.grossMinor(), item.netMinor(),
                        item.vatMinor())).toList(),
                reasons.stream().map(reason -> new CancellationResponse.Reason(reason.source(), reason.code(),
                        reason.text(), actor(reason.addedByType(), reason.addedByRole(), reason.addedById(), null,
                                revealStaffIds), reason.addedAt())).toList(),
                reasons.isEmpty(), amounts, row.requestedAt(), row.decidedAt(),
                actor(row.requestedByType(), row.requestedByRole(), row.requestedById(), row.reportedStaffId(),
                        revealStaffIds),
                row.decidedByType() == null ? null
                        : actor(row.decidedByType(), row.decidedByRole(), row.decidedById(), null, false),
                rejection,
                new CancellationResponse.OrderState(order.getFulfillmentStatus().name(),
                        order.getPaymentStatus().name()));
    }

    /**
     * The author as the stored kind says (GUEST, STAFF, SERVICE). Only ADMIN sees the id of a staff member or of a service
     * and, apart from it, the staff member a service claimed to act for.
     */
    private static CancellationResponse.Actor actor(String type, String role, String id, UUID reportedStaffId,
                                                    boolean reveal) {
        boolean identified = STAFF.equals(type) || SERVICE.equals(type);
        return new CancellationResponse.Actor(type, role, reveal && identified ? id : null,
                reveal && reportedStaffId != null ? reportedStaffId.toString() : null);
    }
}
