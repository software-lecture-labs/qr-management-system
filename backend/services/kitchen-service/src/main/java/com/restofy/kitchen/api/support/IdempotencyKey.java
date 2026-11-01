package com.restofy.kitchen.api.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Parses the {@code Idempotency-Key} header: exactly one canonical UUID, never echoed back. */
public final class IdempotencyKey {

	public static final String HEADER = "Idempotency-Key";
	private static final Pattern STRICT_UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private IdempotencyKey() {
	}

	/**
	 * Several headers or a comma separated list are ambiguous (which one is the command?) and are rejected rather than
	 * resolved by picking one; lenient forms such as {@code 1-1-1-1-1} are rejected too (400).
	 */
	public static UUID parse(List<String> header) {
		if (header.size() != 1 || !STRICT_UUID.matcher(header.get(0)).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be exactly one UUID");
		}
		return UUID.fromString(header.get(0));
	}
}
