package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface TableSessionRepository extends JpaRepository<TableSession, UUID> {
    @Query("select s.id as id, s.restaurantId as restaurantId from TableSession s where s.status=:status "
            + "and (:after is null or s.id > :after) order by s.id")
    List<IdleCandidate> findLifecycleCandidates(TableSessionStatus status, UUID after, Pageable pageable);
    @Query("select (count(s) > 0) from TableSession s where s.restaurantId=:restaurantId and s.tableId=:tableId and s.id<>:sessionId "
            + "and (s.openedAt>=:closedAt or s.status in (com.restofy.qr.domain.enums.TableSessionStatus.OPEN, " +
                    "com.restofy.qr.domain.enums.TableSessionStatus.CLOSING))")
    boolean hasOtherSessionOpenedSince(UUID restaurantId, UUID tableId, UUID sessionId, Instant closedAt);

    @Query("select s.id as id, s.restaurantId as restaurantId from TableSession s where s.status = :status "
            + "and (:after is null or s.id > :after) and (s.idleExpiresAt <= :now or exists "
            + "(select g.id from GuestSession g where g.restaurantId=s.restaurantId and g.tableSessionId=s.id "
            + "and g.status=:guestStatus and g.expiresAt <= :now)) order by s.id")
    List<IdleCandidate> findIdleCandidates(TableSessionStatus status, GuestSessionStatus guestStatus, Instant now,
                                          UUID after, Pageable pageable);

    interface IdleCandidate {
        UUID getId();
        UUID getRestaurantId();
    }

    @Query("select s.tableId from TableSession s where s.restaurantId = :restaurantId and s.id = :id")
    Optional<UUID> locateTableId(UUID restaurantId, UUID id);

    Optional<TableSession> findByRestaurantIdAndId(UUID restaurantId, UUID id);

    Optional<TableSession> findByRestaurantIdAndTableIdAndStatusIn(UUID restaurantId, UUID tableId,
                                                                  Collection<TableSessionStatus> statuses);
}
