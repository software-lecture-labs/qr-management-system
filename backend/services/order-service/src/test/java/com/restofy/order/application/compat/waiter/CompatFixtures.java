package com.restofy.order.application.compat.waiter;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.entity.OrderItemOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.infrastructure.persistence.CancellationStore.ItemRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.ReasonRow;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Plain objects for the pure unit tests of the Waiter compatibility projection. */
final class CompatFixtures {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final UUID RESTAURANT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private CompatFixtures() {
    }

    /** An order of the given gross, one line per entry of {@code unitsAndQuantities} (unit gross, quantity). */
    static Order order(String currency, long[]... unitsAndQuantities) {
        long gross = 0;
        for (long[] line : unitsAndQuantities) {
            gross += line[0] * line[1];
        }
        Order order = new Order(UUID.randomUUID(), "ORD-000001", RESTAURANT, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), currency, gross, 0, gross, null, NOW);
        int lineNo = 0;
        for (long[] line : unitsAndQuantities) {
            order.addItem(new OrderItem(order, ++lineNo, UUID.randomUUID(), "Product " + lineNo, (int) line[1], currency,
                    line[0], 0, line[0] * line[1], line[0] * line[1], 0, null, null, NOW));
        }
        return order;
    }

    static OrderItem item(Order order, int index) {
        return order.getItems().stream().sorted(Comparator.comparingInt(OrderItem::getLineNo))
                .toList().get(index);
    }

    static void option(OrderItem item, String name, SelectionAction action, int quantity, long delta) {
        item.addOption(new OrderItemOption(item, UUID.randomUUID(), action, quantity, name, delta));
    }

    static Row row(Order order, String status, String type, String id, String role, long cancelledGross,
                   long refundRequired, long refunded) {
        boolean decided = !"REQUESTED".equals(status);
        return new Row(UUID.randomUUID(), order.getRestaurantId(), order.getId(), "PARTIAL", status, type, id, role,
                null, NOW, false, "RECEIVED", "NOT_REQUESTED", decided ? "AUTO" : null, decided ? "SYSTEM" : null, null,
                null, decided ? NOW : null, "REJECTED".equals(status) ? "KITCHEN_DECLINED" : null, null,
                order.getCurrency(), order.getCurrentDueMinor(), cancelledGross, cancelledGross, 0, 0, 0, refundRequired,
                refunded, null, null, 0, NOW);
    }

    static ItemRow itemRow(UUID orderItemId, int quantity) {
        return new ItemRow(orderItemId, UUID.randomUUID(), "x", quantity, 100, 0, 100L * quantity, 100L * quantity, 0);
    }

    static ReasonRow reason(String code, String text, String type, String role) {
        return new ReasonRow(type, code, text, type, "someone", role, NOW);
    }
}
