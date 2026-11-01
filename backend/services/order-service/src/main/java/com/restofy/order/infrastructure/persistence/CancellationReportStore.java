package com.restofy.order.infrastructure.persistence;

import com.restofy.order.api.dto.response.CancellationReportResponse.ReasonCount;
import com.restofy.order.api.dto.response.CancellationReportResponse.Totals;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.order.infrastructure.persistence.JdbcTimestamps.timestamp;

/**
 * Read model of the cancellation report. Always scoped to one restaurant; every filter value is bound as a parameter and
 * the SQL text is constant, including optional filters and sorting. Reads only: the records are written by the cancellation use case.
 */
@Repository
public class CancellationReportStore {

    /** @param overdueBefore when set, only REQUESTED rows requested before this instant (waiting too long) */
    public record Filter(UUID restaurantId, Instant from, Instant to, String reasonCode, String scope, String status,
                         Instant overdueBefore) {
    }

    /** What is still waiting for the Kitchen within a filter: how many are overdue and since when the oldest waits. */
    public record Pending(long overdueCount, Instant oldestRequestedAt) {
    }

    public record Line(UUID id, UUID orderId, String orderNo, UUID tableId, String scope, String status,
                       boolean kitchenConfirmationRequired, String decisionSource, String requestedByType,
                       String requestedById, String requestedByRole, UUID reportedStaffId, Instant requestedAt, Instant decidedAt,
                       String rejectionCode, long units, String currency, long previousDueMinor,
                       long cancelledGrossMinor, long cancelledNetMinor, long cancelledVatMinor, long newDueMinor,
                       long collectedMinor, long refundRequiredMinor, long refundedMinor) {
    }

    public record ReasonLine(UUID cancellationId, String source, String code, String text) {
    }

    private static final String STATUS = "status";
    private static final String FILTER = """
            c.restaurant_id = :restaurantId
            AND (CAST(:fromTime AS timestamptz) IS NULL OR c.requested_at >= :fromTime)
            AND (CAST(:toTime AS timestamptz) IS NULL OR c.requested_at < :toTime)
            AND (CAST(:scope AS varchar) IS NULL OR c.scope = :scope)
            AND (CAST(:status AS varchar) IS NULL OR c.status = :status)
            AND (CAST(:overdueBefore AS timestamptz) IS NULL OR (c.status = 'REQUESTED' AND c.requested_at < :overdueBefore))
            AND (CAST(:reasonCode AS varchar) IS NULL OR EXISTS (
                SELECT 1 FROM order_cancellation_reasons fr
                WHERE fr.cancellation_id = c.id AND fr.reason_code = :reasonCode))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CancellationReportStore(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public long count(Filter filter) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM order_cancellations c WHERE " + FILTER,
                parameters(filter), Long.class);
        return total == null ? 0L : total;
    }

    /** Newest request first (or oldest first on request); the id breaks ties at page boundaries. */
    public List<Line> page(Filter filter, boolean oldestFirst, int limit, long offset) {
        var args = parameters(filter).addValue("oldestFirst", oldestFirst)
                .addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("""
                SELECT c.id, c.order_id, o.order_no, o.table_id, c.scope, c.status, c.kitchen_confirmation_required,
                       c.decision_source, c.requested_by_type, c.requested_by_id, c.requested_by_role, c.reported_staff_id, c.requested_at,
                       c.decided_at, c.rejection_code, c.currency, c.previous_due_minor, c.cancelled_gross_minor,
                       c.cancelled_net_minor, c.cancelled_vat_minor, c.new_due_minor, c.collected_minor,
                       c.refund_required_minor, c.refunded_minor,
                       (SELECT coalesce(sum(i.quantity), 0) FROM order_cancellation_items i
                         WHERE i.cancellation_id = c.id) AS units
                FROM order_cancellations c JOIN orders o ON o.restaurant_id = c.restaurant_id AND o.id = c.order_id
                WHERE
                """ + FILTER + """
                ORDER BY CASE WHEN :oldestFirst THEN c.requested_at END ASC,
                         CASE WHEN :oldestFirst THEN c.id END ASC,
                         CASE WHEN NOT :oldestFirst THEN c.requested_at END DESC,
                         CASE WHEN NOT :oldestFirst THEN c.id END DESC
                LIMIT :limit OFFSET :offset
                """, args, (rs, i) -> line(rs));
    }

    public List<ReasonLine> reasonsOf(List<UUID> cancellationIds) {
        if (cancellationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT cancellation_id, source, reason_code, reason_text FROM order_cancellation_reasons
                WHERE cancellation_id IN (:cancellationIds) ORDER BY seq
                """, new MapSqlParameterSource("cancellationIds", cancellationIds),
                (rs, i) -> new ReasonLine(rs.getObject("cancellation_id", UUID.class), rs.getString("source"),
                        rs.getString("reason_code"), rs.getString("reason_text")));
    }

    public Map<String, Long> countByStatus(Filter filter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        RowCallbackHandler readCount = rs -> counts.put(rs.getString(STATUS), rs.getLong("n"));
        jdbc.query("SELECT c.status, count(*) AS n FROM order_cancellations c WHERE " + FILTER
                        + " GROUP BY c.status ORDER BY c.status", parameters(filter),
                readCount);
        return counts;
    }

    /** Money of the APPLIED cancellations, per currency. */
    public List<Totals> totals(Filter filter) {
        return jdbc.query("""
                SELECT c.currency, count(*) AS n, coalesce(sum(c.cancelled_gross_minor), 0) AS gross,
                       coalesce(sum(c.refund_required_minor), 0) AS required, coalesce(sum(c.refunded_minor), 0) AS refunded
                FROM order_cancellations c WHERE
                """ + FILTER + " AND c.status = 'APPLIED' GROUP BY c.currency ORDER BY c.currency",
                parameters(filter), (rs, i) -> new Totals(rs.getString("currency"), rs.getLong("n"), rs.getLong("gross"),
                        rs.getLong("required"), rs.getLong("refunded")));
    }

    public List<ReasonCount> countByReason(Filter filter) {
        return jdbc.query("""
                SELECT r.reason_code, count(DISTINCT r.cancellation_id) AS n FROM order_cancellation_reasons r
                JOIN order_cancellations c ON c.id = r.cancellation_id WHERE
                """ + FILTER + " AND r.reason_code IS NOT NULL GROUP BY r.reason_code ORDER BY n DESC, r.reason_code",
                parameters(filter), (rs, i) -> new ReasonCount(rs.getString("reason_code"), null, rs.getLong("n")));
    }

    public Pending pending(Filter filter, Instant overdueBefore) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE c.requested_at < :pendingCutoff) AS overdue,
                       min(c.requested_at) AS oldest FROM order_cancellations c WHERE
                """ + FILTER + " AND c.status = 'REQUESTED'",
                parameters(filter).addValue("pendingCutoff", Timestamp.from(overdueBefore), Types.TIMESTAMP),
                (rs, i) -> new Pending(rs.getLong("overdue"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()));
    }

    public long countWithoutReason(Filter filter) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM order_cancellations c WHERE " + FILTER
                        + " AND NOT EXISTS (SELECT 1 FROM order_cancellation_reasons r WHERE r.cancellation_id = c.id)",
                parameters(filter), Long.class);
        return total == null ? 0L : total;
    }

    private static MapSqlParameterSource parameters(Filter filter) {
        return new MapSqlParameterSource("restaurantId", filter.restaurantId())
                .addValue("fromTime", timestamp(filter.from()), Types.TIMESTAMP)
                .addValue("toTime", timestamp(filter.to()), Types.TIMESTAMP)
                .addValue("scope", filter.scope(), Types.VARCHAR)
                .addValue(STATUS, filter.status(), Types.VARCHAR)
                .addValue("overdueBefore", timestamp(filter.overdueBefore()), Types.TIMESTAMP)
                .addValue("reasonCode", filter.reasonCode(), Types.VARCHAR);
    }

    private static Line line(ResultSet rs) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("order_no"),
                rs.getObject("table_id", UUID.class), rs.getString("scope"), rs.getString(STATUS),
                rs.getBoolean("kitchen_confirmation_required"), rs.getString("decision_source"),
                rs.getString("requested_by_type"), rs.getString("requested_by_id"), rs.getString("requested_by_role"),
                rs.getObject("reported_staff_id", UUID.class), rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                rs.getString("rejection_code"), rs.getLong("units"), rs.getString("currency"),
                rs.getLong("previous_due_minor"), rs.getLong("cancelled_gross_minor"), rs.getLong("cancelled_net_minor"),
                rs.getLong("cancelled_vat_minor"), rs.getLong("new_due_minor"), rs.getLong("collected_minor"),
                rs.getLong("refund_required_minor"), rs.getLong("refunded_minor"));
    }
}
