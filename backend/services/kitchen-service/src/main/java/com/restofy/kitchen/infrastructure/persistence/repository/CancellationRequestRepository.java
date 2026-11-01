package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, UUID> {

	/** Tenant scoped: a command id of another restaurant is never visible. */
	Optional<CancellationRequest> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);

	/** The order's whole request history, newest first (then by id), so the order is always the same. */
	List<CancellationRequest> findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(UUID restaurantId, UUID orderId);

	/** The order's still-undecided request, if any (the database allows at most one). */
	Optional<CancellationRequest> findByRestaurantIdAndOrderIdAndStatus(UUID restaurantId, UUID orderId,
			CancellationRequestStatus status);
}
