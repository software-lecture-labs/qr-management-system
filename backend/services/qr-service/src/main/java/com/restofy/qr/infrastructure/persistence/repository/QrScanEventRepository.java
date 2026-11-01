package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.QrScanEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QrScanEventRepository extends JpaRepository<QrScanEvent, UUID> {
    long countByRestaurantIdAndTableId(UUID restaurantId, UUID tableId);
    long countByRestaurantIdAndTableSessionId(UUID restaurantId, UUID tableSessionId);
}
