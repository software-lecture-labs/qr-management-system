package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.entity.OrderServeCommand;
import com.restofy.waiter.domain.enums.ServeCommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderServeCommandRepository extends JpaRepository<OrderServeCommand, UUID> {

    /** Tenant- and order-scoped lookup: another restaurant's or another order's command id looks like a missing one. */
    Optional<OrderServeCommand> findByIdAndRestaurantIdAndOrderId(UUID id, UUID restaurantId, UUID orderId);

    Optional<OrderServeCommand> findByRestaurantIdAndRequestedByUserIdAndIdempotencyKey(
            UUID restaurantId, UUID requestedByUserId, String idempotencyKey);

    /** Commands of the given orders that still lack Order's answer, newest first. */
    List<OrderServeCommand> findByRestaurantIdAndOrderIdInAndStatusInOrderByCreatedAtDesc(
            UUID restaurantId, Collection<UUID> orderIds, Collection<ServeCommandStatus> statuses);
}
