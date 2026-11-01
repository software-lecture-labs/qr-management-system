package com.restofy.kitchen.domain.enums;

/**
 * Sales availability of a product. The authoritative record lives in the Menu service; Kitchen only asks for a change
 * and shows what Menu confirmed. This is not a stock quantity (V1 has no quantities, recipes or ingredients).
 */
public enum ProductAvailabilityStatus {
	AVAILABLE,
	OUT_OF_STOCK,
	TEMPORARILY_CLOSED;

	public boolean isClosed() {
		return this != AVAILABLE;
	}
}
