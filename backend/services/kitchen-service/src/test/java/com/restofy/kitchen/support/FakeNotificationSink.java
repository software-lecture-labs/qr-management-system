package com.restofy.kitchen.support;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.NotificationEventSink;
import com.restofy.kitchen.support.FakeOrderEventSink.Mode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TEST-ONLY stand-in for the Notification service's inbound side. It records what was handed over and answers the way the
 * port contract says an adapter would. It proves how Kitchen behaves towards that contract; it proves nothing about a real
 * Notification service, which Kitchen is not connected to (D-10 open).
 */
public class FakeNotificationSink implements NotificationEventSink {

	private final List<OutboundMessage> received = new CopyOnWriteArrayList<>();
	private volatile Mode mode = Mode.DELIVER;

	public FakeNotificationSink mode(Mode mode) {
		this.mode = mode;
		return this;
	}

	public List<OutboundMessage> received() {
		return List.copyOf(received);
	}

	@Override
	public Result deliver(OutboundMessage message) {
		received.add(message);
		return switch (mode) {
			case DELIVER -> Result.delivered();
			case UNAVAILABLE -> Result.retry("HTTP_503");
			case TIMEOUT -> throw new IllegalStateException("simulated timeout http://notification.internal/secret?token=abc");
			case PERMANENT -> Result.permanentFailure("UNSUPPORTED_SCHEMA");
		};
	}
}
