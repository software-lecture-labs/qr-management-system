package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.TableOperationalStatus;

/**
 * Allow-listed table filters; null means "do not filter". All set filters must hold (AND). Calls: {@code hasOpenCall}
 * covers unresolved WAITER and HELP calls, {@code billRequested} unresolved BILL calls.
 */
public record TableFilter(TableOperationalStatus status, Boolean hasActiveOrder, Boolean hasOpenCall,
                          Boolean billRequested) {

    public static final TableFilter NONE = new TableFilter(null, null, null, null);
}
