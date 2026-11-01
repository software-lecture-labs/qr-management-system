package com.restofy.kitchen.application.mapper;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Strict reader for raw "order accepted" payloads, whatever transport finally delivers them. Policy: unknown
 * fields (including any price/tax field, which Kitchen never accepts or recalculates), duplicate keys, scalar
 * coercion (e.g. "2" or 2.5 for an integer), null primitives and oversized payloads are rejected. The error never echoes payload content.
 */
@Component
public class OrderAcceptedMessageReader {

	/** Upper bound for a raw payload (characters); a 200-item order stays far below this. */
	static final int MAX_PAYLOAD_CHARS = 262_144;

	private final JsonMapper strict = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.build();

	public OrderAcceptedMessage read(String payload) {
		if (payload == null || payload.isBlank()) {
			throw new InvalidOrderMessageException(List.of("payload must not be empty"));
		}
		if (payload.length() > MAX_PAYLOAD_CHARS) {
			throw new InvalidOrderMessageException(List.of("payload is too large"));
		}
		try {
			OrderAcceptedMessage message = strict.readValue(payload, OrderAcceptedMessage.class);
			if (message == null) {
				throw new InvalidOrderMessageException(List.of("payload must be a JSON object"));
			}
			return message;
		} catch (JacksonException e) {
			throw new InvalidOrderMessageException(List.of("payload is not a valid order accepted message"));
		}
	}
}
