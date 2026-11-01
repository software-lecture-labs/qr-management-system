package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/** Tenant scoped lookup of the events of one aggregate (an order). */
	List<OutboxEvent> findByRestaurantIdAndAggregateId(UUID restaurantId, UUID aggregateId);
}
