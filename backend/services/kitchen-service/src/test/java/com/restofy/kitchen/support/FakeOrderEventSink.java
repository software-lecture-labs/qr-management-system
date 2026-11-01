package com.restofy.kitchen.support;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.OrderEventSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TEST-ONLY stand-in for the Order service's inbound side. It records what was sent and answers the way the proposed
 * port contract says an adapter would. It proves how Kitchen behaves towards that contract; it proves nothing about a
 * real Order service, which Kitchen is not connected to (D-10 open).
 */
public class FakeOrderEventSink implements OrderEventSink {

	public enum Mode {
		/** A positive acknowledgement of the event id. */
		DELIVER,
		/** 5xx / throttling: an unknown outcome, to be sent again. */
		UNAVAILABLE,
		/** Timeout or connection failure: the adapter throws. */
		TIMEOUT,
		/** A definite, non-repairable rejection. */
		PERMANENT
	}

	private final List<OutboundMessage> received = new CopyOnWriteArrayList<>();
	private volatile Mode mode = Mode.DELIVER;

	public FakeOrderEventSink mode(Mode mode) {
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
			case TIMEOUT -> throw new IllegalStateException("simulated timeout http://order.internal/secret?token=abc");
			case PERMANENT -> Result.permanentFailure("UNSUPPORTED_SCHEMA");
		};
	}
}
