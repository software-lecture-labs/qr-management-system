package com.restofy.order.api.dto.response;

import java.util.List;
import java.util.UUID;

/** Every cancellation of one order, oldest first. */
public record CancellationListResponse(UUID orderId, List<CancellationResponse> cancellations) {

    public CancellationListResponse {
        cancellations = ResponseCollections.copyOf(cancellations);
    }
}
