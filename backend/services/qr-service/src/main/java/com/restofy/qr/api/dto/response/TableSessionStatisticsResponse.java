package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.TableSessionStatus;
import java.time.Instant;
import java.util.UUID;

public record TableSessionStatisticsResponse(UUID restaurantId, UUID tableId, UUID liveSessionId, TableSessionStatus liveSessionStatus,
        long activeGuestCount, long sessionAcceptedScanCount, long tableAcceptedScanCount, Instant checkedAt) {
}
