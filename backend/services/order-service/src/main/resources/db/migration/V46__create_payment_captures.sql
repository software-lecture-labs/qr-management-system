-- Order Service ORDER-04 / commit 1 (range V41-V50): durable, database-enforced identity of real money collections.
--
-- The Payment Service owns payments and refunds. These tables are only the Order Service's LOCAL RECORD of what the
-- Payment Service reported, so that one real collection (capture) can never have a second financial effect, whatever
-- message id it arrives under and however long ago the ordinary inbox rows were cleaned up.
--   * eventId            identifies a MESSAGE
--   * payment_attempt_id identifies one payment ATTEMPT of the guest
--   * capture_id         identifies one REAL collection of money (only PAID carries it)
-- capture_id is unique across the whole Order Service database: the Payment Service issues it as a UUID and the current
-- contract carries no provider or merchant-account field. If Payment later serves several providers or merchant
-- accounts with provider-assigned ids, the contract must add them and this uniqueness widens accordingly (open
-- integration dependency, see docs).

-- One row per capture id: which order it belongs to and what became of it.
--   APPLIED               verified and applied; the order is PAID by this collection
--   PENDING_VERIFICATION  a trusted PAID arrived BEFORE the PENDING of its attempt; it waits (durably) and is verified and
--                         applied when that PENDING arrives, or it becomes NOT_APPLIED if the order is cancelled first
--   NOT_APPLIED           real money that could not be applied (see not_applied_reason); a reconciliation case exists
CREATE TABLE payment_captures (
    id                  uuid         NOT NULL,
    capture_id          uuid         NOT NULL,
    restaurant_id       uuid         NOT NULL,
    order_id            uuid         NOT NULL,
    payment_attempt_id  uuid         NOT NULL,
    amount_minor        bigint       NOT NULL,
    currency            varchar(3)   NOT NULL,
    status              varchar(24)  NOT NULL,
    not_applied_reason  varchar(40),
    source_event_id     uuid         NOT NULL,
    occurred_at         timestamptz  NOT NULL,
    received_at         timestamptz  NOT NULL,
    resolved_at         timestamptz,
    CONSTRAINT pk_payment_captures PRIMARY KEY (id),
    CONSTRAINT uq_payment_captures_capture UNIQUE (capture_id),
    CONSTRAINT fk_payment_captures_order FOREIGN KEY (restaurant_id, order_id) REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_payment_captures_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_payment_captures_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_captures_status CHECK (status IN ('APPLIED', 'PENDING_VERIFICATION', 'NOT_APPLIED')),
    CONSTRAINT ck_payment_captures_reason CHECK (
        (status = 'NOT_APPLIED') = (not_applied_reason IS NOT NULL)
        AND (not_applied_reason IS NULL OR not_applied_reason IN
            ('SECOND_CAPTURE', 'ORDER_CANCELLED', 'PAYMENT_CANCELLED', 'AMOUNT_MISMATCH', 'ATTEMPT_ENDED'))),
    CONSTRAINT ck_payment_captures_resolved CHECK ((status = 'PENDING_VERIFICATION') = (resolved_at IS NULL))
);

-- An order is settled by at most ONE collection.
CREATE UNIQUE INDEX uq_payment_captures_one_applied_per_order ON payment_captures (order_id) WHERE status = 'APPLIED';
-- waiting PAIDs are looked up by order when a PENDING arrives
CREATE INDEX ix_payment_captures_pending ON payment_captures (order_id, payment_attempt_id)
    WHERE status = 'PENDING_VERIFICATION';
CREATE INDEX ix_payment_captures_order ON payment_captures (restaurant_id, order_id);

-- Every money event that needs a human: an append-only list for reconciliation and refund review. (capture_id,
-- order_id, reason) is unique, so the same discrepancy raised again and again is recorded once.
--   SECOND_CAPTURE               a DIFFERENT collection for an order that is already paid (possible double charge)
--   CAPTURE_USED_ON_OTHER_ORDER  this collection already belongs to another order
--   ORDER_CANCELLED / PAYMENT_CANCELLED / ATTEMPT_ENDED / AMOUNT_MISMATCH  money that cannot be applied
CREATE TABLE payment_reconciliation_cases (
    id                      uuid         NOT NULL,
    restaurant_id           uuid         NOT NULL,
    order_id                uuid         NOT NULL,
    capture_id              uuid         NOT NULL,
    payment_attempt_id      uuid         NOT NULL,
    reason                  varchar(40)  NOT NULL,
    reported_amount_minor   bigint       NOT NULL,
    reported_currency       varchar(3)   NOT NULL,
    source_event_id         uuid         NOT NULL,
    occurred_at             timestamptz  NOT NULL,
    fulfillment_status      varchar(20)  NOT NULL,
    payment_status          varchar(20)  NOT NULL,
    created_at              timestamptz  NOT NULL,
    CONSTRAINT pk_payment_reconciliation_cases PRIMARY KEY (id),
    CONSTRAINT uq_payment_reconciliation_case UNIQUE (order_id, capture_id, reason),
    CONSTRAINT fk_payment_reconciliation_order FOREIGN KEY (restaurant_id, order_id) REFERENCES orders (restaurant_id, id),
    CONSTRAINT fk_payment_reconciliation_capture FOREIGN KEY (capture_id) REFERENCES payment_captures (capture_id),
    CONSTRAINT ck_payment_reconciliation_reason CHECK (reason IN ('SECOND_CAPTURE', 'CAPTURE_USED_ON_OTHER_ORDER',
        'ORDER_CANCELLED', 'PAYMENT_CANCELLED', 'ATTEMPT_ENDED', 'AMOUNT_MISMATCH')),
    CONSTRAINT ck_payment_reconciliation_amount CHECK (reported_amount_minor >= 0),
    CONSTRAINT ck_payment_reconciliation_currency CHECK (reported_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_payment_reconciliation_restaurant ON payment_reconciliation_cases (restaurant_id, created_at);

-- ---- database guards ---------------------------------------------------------------------------------------------

-- A capture is never deleted and never rewritten. The only change it may go through is leaving PENDING_VERIFICATION
-- (once) for APPLIED or NOT_APPLIED; after that it is final.
CREATE FUNCTION payment_captures_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment captures are never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.capture_id IS DISTINCT FROM OLD.capture_id
        OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id OR NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.payment_attempt_id IS DISTINCT FROM OLD.payment_attempt_id
        OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'payment capture % is immutable', OLD.capture_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'PENDING_VERIFICATION' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.not_applied_reason IS DISTINCT FROM OLD.not_applied_reason
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at) THEN
        RAISE EXCEPTION 'payment capture % is already %, its outcome is final', OLD.capture_id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_captures_guard
    BEFORE UPDATE OR DELETE ON payment_captures
    FOR EACH ROW EXECUTE FUNCTION payment_captures_guard();

-- A capture can only be APPLIED to an order that really is PAID. Checked at commit, because the capture row and the
-- order's status change are written by the same transaction in either order.
CREATE FUNCTION payment_captures_applied_requires_paid() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM orders o WHERE o.id = NEW.order_id AND o.restaurant_id = NEW.restaurant_id
                AND o.payment_status = 'PAID') THEN
        RAISE EXCEPTION 'capture % is APPLIED but order % is not PAID', NEW.capture_id, NEW.order_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_payment_captures_applied_requires_paid
    AFTER INSERT OR UPDATE ON payment_captures
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment_captures_applied_requires_paid();

CREATE FUNCTION payment_reconciliation_cases_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment reconciliation cases are append-only' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_reconciliation_cases_append_only
    BEFORE UPDATE OR DELETE ON payment_reconciliation_cases
    FOR EACH ROW EXECUTE FUNCTION payment_reconciliation_cases_append_only();
