package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.port.MenuAvailabilityPort;

/**
 * An availability command that did not succeed. {@code kind} says why; {@code code} is a short safe code. Nothing in
 * Kitchen was shown as changed. {@code UNAVAILABLE} means the Menu outcome is UNKNOWN, not that the change failed.
 */
public class ProductAvailabilityException extends RuntimeException {

	private final MenuAvailabilityPort.Kind kind;
	private final String code;

	public ProductAvailabilityException(MenuAvailabilityPort.Kind kind, String code) {
		super("Product availability command failed: " + kind);
		this.kind = kind;
		this.code = code;
	}

	public MenuAvailabilityPort.Kind getKind() {
		return kind;
	}

	public String getCode() {
		return code;
	}
}
