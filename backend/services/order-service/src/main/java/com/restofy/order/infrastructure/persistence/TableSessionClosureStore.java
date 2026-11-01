package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.closure.ClosureBlocker;
import com.restofy.order.domain.closure.ClosureEffect;
import com.restofy.order.domain.closure.ClosureOutcome;
import com.restofy.order.domain.closure.ClosurePurpose;
import com.restofy.order.domain.closure.OrderClosureFacts;
import com.restofy.order.domain.enums.ClosureStatus;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence of table-session lifecycle operations (V48, drifts V49) and the lock that orders them against checkouts
 * (ADR-QR-001 K8/K9).
 *
 * <p>The ordering lock is a PostgreSQL transaction-level advisory lock on (restaurant, table session). A checkout takes
 * it SHARED, so checkouts of one session never wait for each other; a preparation takes it EXCLUSIVE, so it waits for
 * every checkout already in flight and keeps new ones out until it commits. Because it lives in the database, it holds
 * across several Order Service instances. Both writers must call it as the first statement of their transaction. It
 * cannot deadlock with the cart or order-number locks: a preparation takes no other lock.
 */
@Repository
public class TableSessionClosureStore {

    /** The local result the QR Service reported when it confirmed it applied the change. */
    public record Receipt(long sessionVersion, long tableVersion, Instant completedAt, int expiredGuests) {
    }

    public record Closure(UUID operationId, UUID restaurantId, UUID tableSessionId, ClosurePurpose purpose,
                          ClosureEffect effect, String commandDigest, Set<UUID> idleGuestIds, ClosureStatus status,
                          int orderCount, String scopeFingerprint, Instant preparedAt, Instant completedAt,
                          Instant releasedAt, Receipt receipt) {

        /** True while the operation keeps the given guest from placing an order. */
        public boolean fences(UUID guestSessionId) {
            if (status == ClosureStatus.RELEASED) {
                return false;
            }
            return effect != ClosureEffect.IDLE_GUESTS || idleGuestIds.contains(guestSessionId);
        }
    }

    private static final String COLUMNS = "operation_id, restaurant_id, table_session_id, purpose, effect,"
            + " command_digest, idle_guest_ids, status, order_count, scope_fingerprint, prepared_at, completed_at,"
            + " released_at, receipt_session_version, receipt_table_version, receipt_completed_at,"
            + " receipt_expired_guests";

    private final JdbcTemplate jdbc;

    public TableSessionClosureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A checkout: waits only for a preparation in progress. Held until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCheckout(UUID restaurantId, UUID tableSessionId) {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", rs -> {
        }, lockKey(restaurantId, tableSessionId));
    }

    /** A preparation or confirmation: waits for every checkout in flight. Held until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForClosure(UUID restaurantId, UUID tableSessionId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, lockKey(restaurantId, tableSessionId));
    }

    private static String lockKey(UUID restaurantId, UUID tableSessionId) {
        return "table-session-closure:" + restaurantId + ":" + tableSessionId;
    }

    public Optional<Closure> findByOperation(UUID operationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM table_session_closures WHERE operation_id = ?",
                (rs, row) -> map(rs), operationId).stream().findFirst();
    }

    /**
     * The operation that is PREPARED, or COMPLETED with an effect that ended the session, if any (at most one, the
     * database sees to that): the one that stops new orders. A COMPLETED IDLE_GUESTS operation ended nothing and is
     * not returned.
     */
    public Optional<Closure> findLive(UUID restaurantId, UUID tableSessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM table_session_closures WHERE restaurant_id = ?"
                        + " AND table_session_id = ? AND (status = 'PREPARED'"
                        + " OR (status = 'COMPLETED' AND effect <> 'IDLE_GUESTS'))",
                (rs, row) -> map(rs), restaurantId, tableSessionId).stream().findFirst();
    }

    /** An authorised operation about to be stored (state PREPARED). */
    public record Prepared(UUID operationId, UUID restaurantId, UUID tableSessionId, ClosurePurpose purpose,
                           ClosureEffect effect, String commandDigest, Collection<UUID> idleGuestIds, int orderCount,
                           String scopeFingerprint) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertPrepared(Prepared op, Instant now) {
        jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id, purpose, effect,"
                        + " command_digest, idle_guest_ids, status, order_count, scope_fingerprint, prepared_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?)",
                op.operationId(), op.restaurantId(), op.tableSessionId(), op.purpose().name(), op.effect().name(),
                op.commandDigest(),
                op.idleGuestIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")),
                op.orderCount(), op.scopeFingerprint(), JdbcTimestamps.timestamp(now));
    }

    /** Completes the operation; {@code receipt} is the QR Service's local result when it sent one (may be null). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(UUID operationId, Receipt receipt, Instant now) {
        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = ?,"
                        + " receipt_session_version = ?, receipt_table_version = ?, receipt_completed_at = ?,"
                        + " receipt_expired_guests = ? WHERE operation_id = ?",
                JdbcTimestamps.timestamp(now), receipt == null ? null : receipt.sessionVersion(),
                receipt == null ? null : receipt.tableVersion(),
                receipt == null ? null : JdbcTimestamps.timestamp(receipt.completedAt()),
                receipt == null ? null : receipt.expiredGuests(), operationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReleased(UUID operationId, Instant now) {
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = ? WHERE operation_id = ?",
                JdbcTimestamps.timestamp(now), operationId);
    }

    /** What was recorded when an operation was completed although its orders had moved since the preparation. */
    public record Drift(ClosureOutcome outcome, List<ClosureBlocker> blockers, Instant detectedAt) {
    }

    /**
     * Records, once per operation, that it was completed although the facts differed from the ones it was prepared on.
     * Append-only; a second call for the same operation changes nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDrift(Closure closure, String fingerprintAtConfirm, ClosureOutcome outcome,
                            Collection<ClosureBlocker> blockers, Instant now) {
        String names = blockers.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
        jdbc.update("""
                INSERT INTO table_session_closure_drifts (id, operation_id, restaurant_id, table_session_id,
                    fingerprint_at_prepare, fingerprint_at_confirm, outcome_at_confirm, blockers, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id) DO NOTHING
                """, UUID.randomUUID(), closure.operationId(), closure.restaurantId(), closure.tableSessionId(),
                closure.scopeFingerprint(), fingerprintAtConfirm, outcome.name(), names,
                JdbcTimestamps.timestamp(now));
    }

    public Optional<Drift> findDrift(UUID operationId) {
        return jdbc.query("SELECT outcome_at_confirm, blockers, detected_at FROM table_session_closure_drifts"
                        + " WHERE operation_id = ?",
                (rs, row) -> new Drift(ClosureOutcome.valueOf(rs.getString("outcome_at_confirm")),
                        Arrays.stream(rs.getString("blockers").split(",")).filter(b -> !b.isBlank())
                                .map(ClosureBlocker::valueOf).toList(),
                        rs.getTimestamp("detected_at").toInstant()), operationId).stream().findFirst();
    }

    /**
     * A number that never goes down and goes up whenever anything about the orders of the session is recorded (a status
     * change, a cancellation or its decision, a capture or its verification, a reconciliation case): the QR Service's
     * "source revision". At least 1.
     */
    public long scopeRevision(UUID restaurantId, UUID tableSessionId) {
        Long revision = jdbc.queryForObject("""
                SELECT (SELECT count(*) + coalesce(sum(o.version), 0) FROM orders o
                        WHERE o.restaurant_id = ? AND o.table_session_id = ?)
                     + (SELECT count(*) FROM order_status_history h JOIN orders o ON o.id = h.order_id
                        WHERE o.restaurant_id = ? AND o.table_session_id = ?)
                     + (SELECT count(*) + coalesce(sum(c.version), 0) FROM order_cancellations c
                        JOIN orders o ON o.id = c.order_id
                        WHERE o.restaurant_id = ? AND o.table_session_id = ?)
                     + (SELECT count(*) + count(*) FILTER (WHERE p.status <> 'PENDING_VERIFICATION')
                        FROM payment_captures p JOIN orders o ON o.id = p.order_id
                        WHERE o.restaurant_id = ? AND o.table_session_id = ?)
                     + (SELECT count(*) FROM payment_reconciliation_cases r JOIN orders o ON o.id = r.order_id
                        WHERE o.restaurant_id = ? AND o.table_session_id = ?)
                """, Long.class, restaurantId, tableSessionId, restaurantId, tableSessionId, restaurantId,
                tableSessionId, restaurantId, tableSessionId, restaurantId, tableSessionId);
        return Math.max(1L, revision == null ? 0L : revision);
    }

    /**
     * Everything the lifecycle decisions need about every order of the session, whichever guest placed it, tenant
     * scoped. One statement, so the facts of one order are read together.
     */
    public List<OrderClosureFacts> loadFacts(UUID restaurantId, UUID tableSessionId) {
        return jdbc.query("""
                SELECT o.id, o.guest_session_id, o.fulfillment_status, o.payment_status, o.current_due_minor,
                       EXISTS (SELECT 1 FROM order_cancellations c
                               WHERE c.order_id = o.id AND c.status = 'REQUESTED') AS open_cancellation,
                       coalesce((SELECT sum(greatest(c.refund_required_minor - c.refunded_minor, 0))
                                 FROM order_cancellations c
                                 WHERE c.order_id = o.id AND c.status = 'APPLIED'), 0) AS outstanding_refund,
                       EXISTS (SELECT 1 FROM payment_captures p
                               WHERE p.order_id = o.id AND p.status = 'PENDING_VERIFICATION') AS pending_capture,
                       EXISTS (SELECT 1 FROM payment_reconciliation_cases r
                               WHERE r.order_id = o.id) AS reconciliation_case,
                       (SELECT max(c.decided_at) FROM order_cancellations c
                        WHERE c.order_id = o.id AND c.status = 'APPLIED') AS cancellation_safe_at
                FROM orders o
                WHERE o.restaurant_id = ? AND o.table_session_id = ?
                ORDER BY o.id
                """, (rs, row) -> new OrderClosureFacts(rs.getObject("id", UUID.class),
                FulfillmentStatus.valueOf(rs.getString("fulfillment_status")),
                PaymentStatus.valueOf(rs.getString("payment_status")), rs.getLong("current_due_minor"),
                rs.getBoolean("open_cancellation"), rs.getLong("outstanding_refund"),
                rs.getBoolean("pending_capture"), rs.getBoolean("reconciliation_case"),
                rs.getObject("guest_session_id", UUID.class), instant(rs.getTimestamp("cancellation_safe_at"))),
                restaurantId, tableSessionId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Closure map(ResultSet rs) throws SQLException {
        Receipt receipt = rs.getTimestamp("receipt_completed_at") == null ? null
                : new Receipt(rs.getLong("receipt_session_version"), rs.getLong("receipt_table_version"),
                rs.getTimestamp("receipt_completed_at").toInstant(), rs.getInt("receipt_expired_guests"));
        Set<UUID> idleGuests = new TreeSet<>();
        Arrays.stream(rs.getString("idle_guest_ids").split(",")).filter(id -> !id.isBlank())
                .map(UUID::fromString).forEach(idleGuests::add);
        return new Closure(rs.getObject("operation_id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                rs.getObject("table_session_id", UUID.class), ClosurePurpose.valueOf(rs.getString("purpose")),
                ClosureEffect.valueOf(rs.getString("effect")), rs.getString("command_digest"), idleGuests,
                ClosureStatus.valueOf(rs.getString("status")), rs.getInt("order_count"),
                rs.getString("scope_fingerprint"), rs.getTimestamp("prepared_at").toInstant(),
                instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("released_at")), receipt);
    }
}
