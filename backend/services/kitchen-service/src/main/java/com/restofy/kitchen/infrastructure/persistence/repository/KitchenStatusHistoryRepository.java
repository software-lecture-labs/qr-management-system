package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitchenStatusHistoryRepository extends JpaRepository<KitchenStatusHistory, UUID> {

	List<KitchenStatusHistory> findByRestaurantIdAndKitchenOrderIdOrderByOccurredAtAsc(UUID restaurantId,
			UUID kitchenOrderId);

	/** The change a command id has already caused in this restaurant, if any (tenant scoped). */
	Optional<KitchenStatusHistory> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);
}
