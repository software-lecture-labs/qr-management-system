package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.service.KitchenEventRecorder;

/**
 * The Notification service's side of the outbox (destination {@code NOTIFICATION}). There is NO production
 * implementation. The Notification service alone decides who is told (roles, people), keeps read state and removes
 * duplicates by event id; Kitchen only hands over the event. See {@link EventSink} for the contract.
 */
public interface NotificationEventSink extends EventSink {

	@Override
	default String destination() {
		return KitchenEventRecorder.NOTIFICATION_DESTINATION;
	}
}
