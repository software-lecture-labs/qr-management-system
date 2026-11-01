package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.response.DailyKitchenReportResponse;
import com.restofy.kitchen.api.support.QueryParams;
import com.restofy.kitchen.application.service.DailyReportService;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kitchen/reports")
public class KitchenReportController {

	private final DailyReportService reports;

	public KitchenReportController(DailyReportService reports) {
		this.reports = reports;
	}

	/**
	 * The kitchen's numbers for one business day: orders received, what became of them, what became ready or was
	 * cancelled that day, and the waste total with its breakdown by reason. KITCHEN and ADMIN may read. Both
	 * {@code date} (yyyy-MM-dd) and {@code timeZone} (an IANA region such as Europe/Istanbul) are required: Kitchen has
	 * no verified source for a restaurant's time zone and does not guess one. No money, profit or stock is reported.
	 */
	@GetMapping("/daily")
	public DailyKitchenReportResponse daily(@AuthenticationPrincipal KitchenPrincipal principal,
			@RequestParam(required = false) String date, @RequestParam(required = false) String timeZone) {
		return reports.daily(principal.restaurantId(), QueryParams.requireDate("date", date), QueryParams.requireZone("timeZone", timeZone));
	}
}
