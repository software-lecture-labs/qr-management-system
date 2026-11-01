package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {
    long countByRestaurantIdAndTableSessionId(UUID restaurantId, UUID tableSessionId);
    long countByRestaurantIdAndTableSessionIdAndStatus(UUID restaurantId, UUID tableSessionId, GuestSessionStatus status);
    List<GuestSession> findByRestaurantIdAndTableSessionIdAndStatus(UUID restaurantId, UUID tableSessionId, GuestSessionStatus status);

    @Query("select count(g) from GuestSession g where g.restaurantId = :restaurantId and g.tableSessionId = :tableSessionId "
            + "and g.status = :status and g.endedAt is null and g.joinedAt <= :now and g.lastActivityAt <= :now "
            + "and (g.expiresAt is null or g.expiresAt > :now or g.id in :protectedIds)")
    long countUsable(UUID restaurantId, UUID tableSessionId, GuestSessionStatus status, Instant now, Set<UUID> protectedIds);

    // Only immutable IDs before locking; do not cache mutable guest/parent state here.
    @Query("select g.id as id, g.restaurantId as restaurantId, g.tableSessionId as tableSessionId from " +
            "GuestSession g where g.tokenDigest = :digest")
    Optional<GuestLocation> locateByDigest(String digest);

    Optional<GuestSession> findByRestaurantIdAndTableSessionIdAndId(UUID restaurantId, UUID tableSessionId, UUID id);

    interface GuestLocation {
        UUID getId();
        UUID getRestaurantId();
        UUID getTableSessionId();
    }
}
