package com.restofy.kitchen.application.mapper;

import com.restofy.kitchen.application.contract.OptionsSnapshot;
import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Converts between order options and the stored, versioned jsonb snapshot. */
@Component
public class OptionsSnapshotCodec {

	private final JsonMapper jsonMapper;

	public OptionsSnapshotCodec(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public String toJson(List<OrderAcceptedMessage.Option> options) {
		List<OptionsSnapshot.Line> lines = options.stream()
				.map(o -> new OptionsSnapshot.Line(o.action(), o.optionId(), o.name(), o.quantity(), o.note()))
				.toList();
		return jsonMapper.writeValueAsString(new OptionsSnapshot(OptionsSnapshot.CURRENT_VERSION, lines));
	}

	/** Reads a stored snapshot; a snapshot of an unknown schema version is refused rather than guessed at. */
	public OptionsSnapshot fromJson(String json) {
		try {
			OptionsSnapshot snapshot = jsonMapper.readValue(json, OptionsSnapshot.class);
			if (snapshot.schemaVersion() != OptionsSnapshot.CURRENT_VERSION || snapshot.options() == null) {
				throw new IllegalStateException("Unsupported options snapshot schema version " + snapshot.schemaVersion());
			}
			return snapshot;
		} catch (JacksonException e) {
			throw new IllegalStateException("Stored options snapshot is not readable", e);
		}
	}
}
