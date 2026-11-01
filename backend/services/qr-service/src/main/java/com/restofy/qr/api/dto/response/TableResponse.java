package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.TableOperationalStatus;
import java.time.Instant;
import java.util.UUID;

public record TableResponse(UUID id, UUID restaurantId, String tableNumber, String displayName,
                            Integer capacity, TableOperationalStatus operationalStatus,
                            boolean isActive, long version, Instant createdAt, Instant updatedAt) {
}
