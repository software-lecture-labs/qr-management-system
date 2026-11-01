package com.restofy.kitchen.support;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.domain.enums.OptionAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test fixture standing in for the Order service until a real transport contract exists. */
public final class OrderMessages {

	private OrderMessages() {
	}

	public static OrderAcceptedMessage.Option option(OptionAction action, String name, int quantity, String note) {
		return new OrderAcceptedMessage.Option(action, UUID.randomUUID(), name, quantity, note);
	}

	public static OrderAcceptedMessage.Item item(String product, int quantity, String note,
			OrderAcceptedMessage.Option... options) {
		return new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), product, quantity, note,
				List.of(options));
	}

	public static OrderAcceptedMessage message(UUID restaurantId, UUID orderId, UUID tableSessionId, String table,
			String orderNumber, long version, UUID eventId, OrderAcceptedMessage.Item... items) {
		return new OrderAcceptedMessage(eventId, 1, Instant.now(), "corr-1", restaurantId, orderId, version,
				orderNumber, tableSessionId, table, List.of(items));
	}

	public static OrderAcceptedMessage simple(UUID restaurantId, UUID orderId) {
		return message(restaurantId, orderId, UUID.randomUUID(), "T1", "A-" + orderId.toString().substring(0, 6), 1,
				UUID.randomUUID(), item("Burger", 2, null));
	}

	public static OrderAcceptedMessage withVersion(OrderAcceptedMessage m, long version, UUID eventId) {
		return new OrderAcceptedMessage(eventId, m.schemaVersion(), m.occurredAt(), m.correlationId(), m.restaurantId(),
				m.orderId(), version, m.orderNumber(), m.tableSessionId(), m.tableNumber(), m.items());
	}
}
