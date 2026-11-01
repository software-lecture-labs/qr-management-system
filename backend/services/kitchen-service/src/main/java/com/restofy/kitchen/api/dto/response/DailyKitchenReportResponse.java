package com.restofy.kitchen.api.dto.response;

import com.restofy.kitchen.domain.enums.WasteReason;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The kitchen's numbers for one business day of one restaurant. The day is {@code [windowStart, windowEnd)} (UTC
 * instants) of {@code date} in {@code timeZone}. The numbers answer different questions and must not be added up or
 * swapped for one another:
 * <ul>
 * <li>{@code orders.received}: orders the kitchen RECEIVED in the window.</li>
 * <li>{@code orders.currentStatusOfReceived}: the status those received orders have NOW (not at midnight), so a past day
 * shows what became of that day's orders; its four numbers add up to {@code received}.</li>
 * <li>{@code orders.duringDay}: transitions that HAPPENED in the window, whenever the order was received.</li>
 * <li>{@code waste}: entries RECORDED in the window; quantity is a sum of portions, records a count of entries.</li>
 * </ul>
 * {@code dayComplete} is false while the window has not ended yet, so the numbers can still change. The counting rule
 * follows the proposal for open decision D-12; it is not a final product decision.
 */
public record DailyKitchenReportResponse(
		LocalDate date,
		String timeZone,
		Instant windowStart,
		Instant windowEnd,
		boolean dayComplete,
		Orders orders,
		Waste waste) {

	public record Orders(long received, CurrentStatus currentStatusOfReceived, DuringDay duringDay) {
	}

	public record CurrentStatus(long waiting, long preparing, long ready, long cancelled) {
	}

	public record DuringDay(long becameReady, long cancelled) {
	}

	public record Waste(long totalQuantity, long recordCount, List<ReasonTotal> byReason) {
	}

	public record ReasonTotal(WasteReason reasonCode, long quantity, long records) {
	}
}
