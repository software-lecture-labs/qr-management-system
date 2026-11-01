package com.restofy.waiter.domain.enums;

/** Which table sessions the order list covers. CURRENT is the default so old sessions never mix in. */
public enum OrderSessionScope {
    /** Only orders of the table sessions that are live now. */
    CURRENT,
    /** Orders of every session, including closed ones (history). */
    ALL
}
