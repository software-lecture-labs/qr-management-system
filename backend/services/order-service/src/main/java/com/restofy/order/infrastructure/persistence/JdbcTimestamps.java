package com.restofy.order.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;

/** The one conversion of an optional {@link Instant} into a JDBC parameter, shared by the JDBC stores. */
final class JdbcTimestamps {

    private JdbcTimestamps() {
    }

    /** {@code null} stays {@code null} (SQL NULL); every other instant is bound as a timestamp. */
    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
