package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.domain.entity.OrderServeCommand;
import com.restofy.waiter.domain.enums.ServeCommandStatus;
import com.restofy.waiter.infrastructure.persistence.repository.OrderServeCommandRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Finds, for a set of orders, the serve command whose outcome is still unknown, so order views can point to it.
 * One query for all orders of a page. The hint is not a delivery fact; Order's own fields remain the truth.
 */
@Component
public class ServeCommandHints {

    private final OrderServeCommandRepository commands;

    public ServeCommandHints(OrderServeCommandRepository commands) {
        this.commands = commands;
    }

    public Map<UUID, OrderResponse.ServeCommandHint> forOrders(UUID restaurantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        Map<UUID, OrderResponse.ServeCommandHint> hints = new HashMap<>();
        for (OrderServeCommand command : commands.findByRestaurantIdAndOrderIdInAndStatusInOrderByCreatedAtDesc(
                restaurantId, orderIds, ServeCommandStatus.UNRESOLVED)) {
            hints.putIfAbsent(command.getOrderId(),
                    new OrderResponse.ServeCommandHint(command.getId(), command.getStatus(), command.getCreatedAt()));
        }
        return hints;
    }
}
