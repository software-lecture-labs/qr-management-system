/**
 * Persistence of the Order Service. Two technologies are used on purpose, each for the kind of work it is right for; the
 * choice is not "JPA everywhere" and must not be flattened into it.
 *
 * <h2>JPA (Spring Data repositories, {@code repository/})</h2>
 * Aggregates that are loaded, changed by domain methods and saved as a whole: {@code Cart} (with lines and options) and
 * {@code Order}. Their invariants live in the entity (status transitions, currency), optimistic locking uses
 * {@code @Version}, and a row lock is taken with {@code @Lock(PESSIMISTIC_WRITE)} where use cases must be serialized
 * (the cart of one guest, the order row). Where one statement must not be an entity round trip, the repository holds a
 * native query next to the entity ({@code CartRepository#insertActiveIfAbsent}: {@code INSERT ... ON CONFLICT DO NOTHING}).
 *
 * <h2>JdbcTemplate (the {@code *Store} classes and the outbox writer)</h2>
 * <ul>
 *   <li><b>Idempotency, inbox, outbox, status history, payment captures, cancellations</b>: records that are written once
 *       and are append-only or decided once. They are not aggregates to load and mutate; the database owns their rules
 *       (unique keys, triggers, deferred constraints) and the code needs the exact statement and its row count
 *       ({@code INSERT ... ON CONFLICT}, conditional {@code UPDATE ... WHERE status = ...}). They require the business
 *       transaction ({@code MANDATORY}) so a record exists if and only if the change it describes was committed.</li>
 *   <li><b>Order numbers</b>: one atomic upsert on a counter row; an entity or a read-then-write would race.</li>
 *   <li><b>Housekeeping and retention</b>: bounded batches with {@code FOR UPDATE SKIP LOCKED}, which JPQL cannot express.</li>
 *   <li><b>Reports</b>: a read model with constant SQL, bound parameters and aggregate functions, scoped to one restaurant.</li>
 * </ul>
 *
 * <h2>Rules that keep the mix consistent</h2>
 * One kind of work uses one technology: the same operation is never implemented once with JPA and once with JDBC. Both
 * run in the same Spring transaction and the same connection, so a JPA change and a JDBC record commit or roll back
 * together. Every SQL text is constant, every value is a bound parameter, and every statement carries the tenant
 * ({@code restaurant_id}) where a tenant exists.
 */
package com.restofy.order.infrastructure.persistence;
