package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {
    boolean existsByRestaurantIdAndTableIdAndStatus(UUID restaurantId, UUID tableId, QrCodeStatus status);

    Optional<QrCode> findByRestaurantIdAndTableIdAndId(UUID restaurantId, UUID tableId, UUID id);

    Page<QrCode> findByRestaurantIdAndTableId(UUID restaurantId, UUID tableId, Pageable pageable);

    // Locate only immutable IDs before acquiring the shared table lock. Do not
    // cache a QrCode entity before the lock, which could hide a concurrent revoke.
    @Query("select q.id as id, q.restaurantId as restaurantId, q.tableId as tableId from QrCode q where q.tokenDigest = :digest")
    Optional<TokenLocation> locateByDigest(@Param("digest") String digest);

    interface TokenLocation {
        UUID getId();
        UUID getRestaurantId();
        UUID getTableId();
    }
}
