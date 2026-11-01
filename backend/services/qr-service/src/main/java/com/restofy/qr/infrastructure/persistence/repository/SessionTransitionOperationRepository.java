package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.SessionTransitionOperation;
import com.restofy.qr.domain.SessionTransition.Stage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionTransitionOperationRepository extends JpaRepository<SessionTransitionOperation, UUID> {
    Optional<SessionTransitionOperation> findByRestaurantIdAndId(UUID restaurantId, UUID id);
    @Query("select o.sessionId from SessionTransitionOperation o where o.restaurantId=:restaurantId and o.id=:id")
    Optional<UUID> locateSessionId(UUID restaurantId, UUID id);
    Optional<SessionTransitionOperation> findByRestaurantIdAndSessionIdAndStageIn(UUID restaurantId, UUID sessionId, Collection<Stage> stages);
    @Query("select o from SessionTransitionOperation o where o.stage in :stages and (:after is null or o.id > :after) order by o.id")
    List<SessionTransitionOperation> findPending(Collection<Stage> stages, UUID after, Pageable pageable);
}
