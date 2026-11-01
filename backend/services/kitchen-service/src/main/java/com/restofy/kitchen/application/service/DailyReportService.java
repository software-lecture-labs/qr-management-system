package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.response.DailyKitchenReportResponse;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.WasteReason;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.WasteRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts for one business day of one restaurant, from what the kitchen itself knows (no Menu, no money). The day is
 * the calendar date in the given IANA time zone, converted to the UTC window [start of the date, start of the next
 * date); across a daylight-saving change that window is 23 or 25 hours long. The time zone is a caller input: Kitchen
 * has no verified source for a restaurant's zone yet and never assumes one. See {@link DailyKitchenReportResponse} for
 * what each number means.
 */
@Service
public class DailyReportService {

	private final KitchenOrderRepository orders;
	private final WasteRecordRepository waste;
	private final Clock clock;

	public DailyReportService(KitchenOrderRepository orders, WasteRecordRepository waste, Clock clock) {
		this.orders = orders;
		this.waste = waste;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public DailyKitchenReportResponse daily(UUID restaurantId, LocalDate date, ZoneId zone) {
		Instant start = date.atStartOfDay(zone).toInstant();
		Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

		Map<KitchenOrderStatus, Long> byStatus = new EnumMap<>(KitchenOrderStatus.class);
		for (Object[] row : orders.countReceivedByStatus(restaurantId, start, end)) {
			byStatus.put((KitchenOrderStatus) row[0], (Long) row[1]);
		}
		DailyKitchenReportResponse.CurrentStatus current = new DailyKitchenReportResponse.CurrentStatus(
				byStatus.getOrDefault(KitchenOrderStatus.WAITING, 0L), byStatus.getOrDefault(KitchenOrderStatus.PREPARING, 0L),
				byStatus.getOrDefault(KitchenOrderStatus.READY, 0L), byStatus.getOrDefault(KitchenOrderStatus.CANCELLED, 0L));
		// "received" is the sum of the very same grouped rows, so the four statuses always add up to it.
		long received = current.waiting() + current.preparing() + current.ready() + current.cancelled();
		DailyKitchenReportResponse.DuringDay during = new DailyKitchenReportResponse.DuringDay(
				orders.countByRestaurantIdAndReadyAtGreaterThanEqualAndReadyAtLessThan(restaurantId, start, end),
				orders.countByRestaurantIdAndCancelledAtGreaterThanEqualAndCancelledAtLessThan(restaurantId, start, end));

		Map<WasteReason, long[]> byReason = new EnumMap<>(WasteReason.class);
		for (Object[] row : waste.totalsByReason(restaurantId, start, end)) {
			byReason.put((WasteReason) row[0], new long[] { (Long) row[1], (Long) row[2] });
		}
		List<DailyKitchenReportResponse.ReasonTotal> reasons = new ArrayList<>();
		long totalQuantity = 0;
		long totalRecords = 0;
		for (WasteReason reason : WasteReason.values()) {
			long[] totals = byReason.getOrDefault(reason, new long[] { 0, 0 });
			reasons.add(new DailyKitchenReportResponse.ReasonTotal(reason, totals[0], totals[1]));
			totalQuantity += totals[0];
			totalRecords += totals[1];
		}
		return new DailyKitchenReportResponse(date, zone.getId(), start, end, !end.isAfter(clock.instant()),
				new DailyKitchenReportResponse.Orders(received, current, during),
				new DailyKitchenReportResponse.Waste(totalQuantity, totalRecords, List.copyOf(reasons)));
	}
}
