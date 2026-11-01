package com.restofy.kitchen.domain.exception;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;

/** The command is not allowed from the order's current status; nothing was changed. */
public class InvalidKitchenTransitionException extends RuntimeException {

	private final KitchenOrderStatus currentStatus;
	private final KitchenOrderStatus targetStatus;

	public InvalidKitchenTransitionException(KitchenOrderStatus currentStatus, KitchenOrderStatus targetStatus) {
		super(currentStatus == targetStatus
				? "Order is already " + currentStatus + " and this command is not a repeat of the one that did it"
				: "Order is " + currentStatus + "; it cannot move to " + targetStatus);
		this.currentStatus = currentStatus;
		this.targetStatus = targetStatus;
	}

	public KitchenOrderStatus getCurrentStatus() {
		return currentStatus;
	}

	public KitchenOrderStatus getTargetStatus() {
		return targetStatus;
	}
}
