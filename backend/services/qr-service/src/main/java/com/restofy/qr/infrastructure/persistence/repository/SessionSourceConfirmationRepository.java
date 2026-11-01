package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.SessionSourceConfirmation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionSourceConfirmationRepository extends JpaRepository<SessionSourceConfirmation, UUID> {
    Optional<SessionSourceConfirmation> findByRestaurantIdAndOperationId(UUID restaurantId, UUID operationId);
}
