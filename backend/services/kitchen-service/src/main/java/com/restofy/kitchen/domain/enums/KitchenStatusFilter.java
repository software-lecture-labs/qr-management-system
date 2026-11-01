package com.restofy.kitchen.domain.enums;

import java.util.Set;

/** Queue filter accepted by the list API. {@code ACTIVE} is the working queue: WAITING plus PREPARING. */
public enum KitchenStatusFilter {
	WAITING(Set.of(KitchenOrderStatus.WAITING)),
	PREPARING(Set.of(KitchenOrderStatus.PREPARING)),
	READY(Set.of(KitchenOrderStatus.READY)),
	CANCELLED(Set.of(KitchenOrderStatus.CANCELLED)),
	ACTIVE(Set.of(KitchenOrderStatus.WAITING, KitchenOrderStatus.PREPARING));

	private final Set<KitchenOrderStatus> statuses;

	KitchenStatusFilter(Set<KitchenOrderStatus> statuses) {
		this.statuses = statuses;
	}

	public Set<KitchenOrderStatus> statuses() {
		return statuses;
	}
}
