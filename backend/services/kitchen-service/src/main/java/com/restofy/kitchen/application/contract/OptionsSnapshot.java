package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.OptionAction;

import java.util.List;
import java.util.UUID;

/** Versioned shape of the {@code options_snapshot} jsonb column. */
public record OptionsSnapshot(int schemaVersion, List<Line> options) {

	public static final int CURRENT_VERSION = 1;

	public record Line(OptionAction action, UUID optionId, String name, int quantity, String note) {
	}
}
