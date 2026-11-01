package com.restofy.kitchen.application.service;

public enum IngestOutcome {
	/** New kitchen order stored as WAITING. */
	CREATED,
	/** Same event or same order version already stored; nothing changed. */
	DUPLICATE,
	/** Older order version than the stored one; ignored so it cannot overwrite newer data. */
	STALE_IGNORED,
	/** Newer version of an already stored order; not applied (follow-up changes are an open decision, D-02). */
	NEWER_VERSION_NOT_APPLIED
}
