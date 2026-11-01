package com.restofy.order.application.mapper;

import com.restofy.order.api.dto.response.CartResponse.ExtraIngredient;
import com.restofy.order.api.dto.response.CartResponse.RemovedIngredient;
import com.restofy.order.api.dto.response.OrderDetailResponse;
import com.restofy.order.api.dto.response.OrderResponse;
import com.restofy.order.api.dto.response.OrderResponse.Amounts;
import com.restofy.order.api.dto.response.OrderResponse.OrderItemResponse;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.entity.OrderItemOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.domain.event.OrderPlacedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Maps an order to its API representation and to the OrderPlaced event. Both read the same immutable snapshot. */
@Component
public class OrderMapper {

    private static final Comparator<OrderItem> ITEM_ORDER =
            Comparator.comparingInt(OrderItem::getLineNo);
    private static final Comparator<OrderItemOption> OPTION_ORDER =
            Comparator.comparing(OrderItemOption::getNameSnapshot).thenComparing(OrderItemOption::getOptionId);

    public OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().sorted(ITEM_ORDER).map(item -> new OrderItemResponse(
                item.getId(), item.getProductId(), item.getProductNameSnapshot(), item.getOrderedQuantity(),
                item.getCancelledQuantity(), item.getRemainingQuantity(),
                removed(item).stream().map(o -> new RemovedIngredient(o.getOptionId(), o.getNameSnapshot())).toList(),
                extras(item).stream().map(o -> new ExtraIngredient(o.getOptionId(), o.getNameSnapshot(),
                        o.getQuantity(), o.getUnitPriceDeltaMinor())).toList(),
                item.getNote(), item.getUnitGrossMinor(), item.getTaxRateBps(), item.getLineGrossMinor(),
                item.getLineNetMinor(), item.getLineVatMinor())).toList();
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getTableSessionId(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), order.getCurrency(),
                new Amounts(order.getInitialNetMinor(), order.getInitialVatMinor(), order.getInitialGrossMinor()),
                items, order.getNote(), order.getPlacedAt());
    }

    public OrderDetailResponse toDetail(Order order) {
        OrderResponse base = toResponse(order);
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), order.getCurrency(),
                base.amounts(), order.getCurrentDueMinor(), cancelledGross(order), activeGross(order), base.items(),
                order.getNote(), order.getPlacedAt(), order.getUpdatedAt(), order.getVersion());
    }

    /**
     * What the kitchen may see of an order: the same lines, quantities, extras, removed ingredients and notes, but no
     * payment status, no amounts and no prices.
     */
    public OrderDetailResponse toKitchenDetail(Order order) {
        OrderResponse base = toResponse(order);
        List<OrderItemResponse> items = base.items().stream().map(item -> new OrderItemResponse(item.orderItemId(),
                item.productId(), item.name(), item.quantity(), item.cancelledQuantity(), item.remainingQuantity(),
                item.removedIngredients(),
                item.extras().stream().map(e -> new ExtraIngredient(e.ingredientId(), e.name(), e.quantity(), null))
                        .toList(), item.note(), null, null, null, null, null)).toList();
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getTableId(), order.getTableSessionId(),
                order.getFulfillmentStatus().name(), null, null, null, null, null, null, items, order.getNote(),
                order.getPlacedAt(), order.getUpdatedAt(), order.getVersion());
    }

    public OrderPlacedEvent toPlacedEvent(Order order, UUID eventId, String correlationId, Instant occurredAt) {
        List<OrderPlacedEvent.Item> items = order.getItems().stream().sorted(ITEM_ORDER)
                .map(item -> new OrderPlacedEvent.Item(item.getId(), item.getProductId(),
                        item.getProductNameSnapshot(), item.getOrderedQuantity(), item.getNote(),
                        removed(item).stream().map(o -> new OrderPlacedEvent.Ingredient(o.getOptionId(),
                                o.getNameSnapshot())).toList(),
                        extras(item).stream().map(o -> new OrderPlacedEvent.Extra(o.getOptionId(),
                                o.getNameSnapshot(), o.getQuantity())).toList(),
                        item.getUnitGrossMinor(), item.getTaxRateBps(), item.getLineGrossMinor()))
                .toList();
        return new OrderPlacedEvent(eventId, OrderPlacedEvent.TYPE, OrderPlacedEvent.SCHEMA_VERSION, occurredAt,
                correlationId, order.getRestaurantId(), order.getTableId(), order.getTableSessionId(),
                order.getGuestSessionId(), order.getId(), order.getOrderNo(), order.getFulfillmentStatus().name(),
                order.getCurrency(), new OrderPlacedEvent.Amounts(order.getInitialNetMinor(),
                order.getInitialVatMinor(), order.getInitialGrossMinor()), order.getNote(), order.getPlacedAt(), items);
    }

    private static long activeGross(Order order) {
        return order.getItems().stream().mapToLong(item -> item.getUnitGrossMinor() * item.getRemainingQuantity()).sum();
    }

    private static long cancelledGross(Order order) {
        return order.getInitialGrossMinor() - activeGross(order);
    }

    private static List<OrderItemOption> removed(OrderItem item) {
        return item.getOptions().stream().filter(o -> o.getAction() == SelectionAction.REMOVE).sorted(OPTION_ORDER)
                .toList();
    }

    private static List<OrderItemOption> extras(OrderItem item) {
        return item.getOptions().stream().filter(o -> o.getAction() == SelectionAction.ADD).sorted(OPTION_ORDER)
                .toList();
    }
}
