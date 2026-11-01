package com.restofy.kitchen.api.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Shared rules for optional query parameters: <b>absent</b> uses the default, while <b>present but empty, blank or
 * invalid</b> is 400. Values are never echoed back.
 */
public final class QueryParams {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	private static final int MIN_REPORT_YEAR = 2000;
	private static final int MAX_REPORT_YEAR = 2100;

	private QueryParams() {
	}

	public static int parseInt(String name, String value, int defaultValue, int min, int max) {
		if (value == null) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed >= min && parsed <= max) {
				return parsed;
			}
		} catch (NumberFormatException _) {
			// falls through to the same 400 as an out-of-range value
		}
		throw badRequest(max == Integer.MAX_VALUE ? name + " must be an integer >= " + min
				: name + " must be an integer between " + min + " and " + max);
	}

	/** Optional ISO-8601 instant such as 2026-10-17T10:00:00Z: absent is null, present but invalid is 400. */
	public static Instant parseInstant(String name, String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException _) {
			throw badRequest(name + " must be an ISO-8601 instant such as 2026-10-17T10:00:00Z");
		}
	}

	/** Optional UUID: absent is null, present but invalid is 400. */
	public static UUID parseUuid(String name, String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException _) {
			throw badRequest(name + " must be a UUID");
		}
	}

	/**
	 * Mandatory calendar date yyyy-MM-dd between the years 2000 and 2100: absent, empty, invalid or absurd is 400
	 * (an extreme year would otherwise overflow while the next day is computed).
	 */
	public static LocalDate requireDate(String name, String value) {
		try {
			if (value != null) {
				LocalDate date = LocalDate.parse(value);
				if (date.getYear() >= MIN_REPORT_YEAR && date.getYear() <= MAX_REPORT_YEAR) {
					return date;
				}
			}
		} catch (DateTimeParseException _) {
			// falls through to the same 400 as a missing value
		}
		throw badRequest(name + " is required and must be a date such as 2026-10-20 (years 2000 to 2100)");
	}

	/**
	 * Mandatory IANA region such as Europe/Istanbul: absent, empty, unknown, or an offset such as +03:00 or UTC+3 is
	 * 400. A fixed offset would silently ignore daylight saving, so only named regions are accepted.
	 */
	public static ZoneId requireZone(String name, String value) {
		if (value != null && ZoneId.getAvailableZoneIds().contains(value)) {
			return ZoneId.of(value);
		}
		throw badRequest(name + " is required and must be an IANA time zone such as Europe/Istanbul");
	}

	public static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
