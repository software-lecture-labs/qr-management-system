package com.restofy.waiter.api.dto.response;

import java.util.List;

/** Table summary plus its unresolved calls (waiter, help and bill) of the live session, oldest first. */
public record TableDetailResponse(TableSummaryResponse table, List<ServiceRequestResponse> openServiceRequests) {
}
