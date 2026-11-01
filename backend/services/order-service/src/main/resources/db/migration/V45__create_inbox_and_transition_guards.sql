-- Order Service ORDER-03 (range V41-V50): inbox for events received from other services, and database guards for the
-- fulfillment and payment lifecycles.

-- One row per processed external event. (source, event_id) is unique, so a redelivered event is recognised and never
-- applied twice. The row is written in the same transaction as the change it caused: both exist or neither does.
CREATE TABLE inbox_events (
    id             uuid         NOT NULL,
    source         varchar(30)  NOT NULL,
    event_id       uuid         NOT NULL,
    restaurant_id  uuid         NOT NULL,
    order_id       uuid         NOT NULL,
    event_type     varchar(60)  NOT NULL,
    outcome        varchar(40)  NOT NULL,
    received_at    timestamptz  NOT NULL,
    CONSTRAINT pk_inbox_events PRIMARY KEY (id),
    CONSTRAINT uq_inbox_events_source_event UNIQUE (source, event_id),
    CONSTRAINT fk_inbox_events_order FOREIGN KEY (restaurant_id, order_id) REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_inbox_events_source CHECK (source IN ('KITCHEN', 'PAYMENT')),
    -- a PAID message waiting for its PENDING has its own outcome (PENDING_VERIFICATION)
    CONSTRAINT ck_inbox_events_outcome
        CHECK (outcome IN ('APPLIED', 'IGNORED_STALE', 'IGNORED_ORDER_CLOSED', 'PENDING_VERIFICATION'))
);

CREATE INDEX ix_inbox_events_order ON inbox_events (restaurant_id, order_id);

-- The only fulfillment moves that may ever happen: one step forward along
-- RECEIVED > PREPARING > READY > SERVED > COMPLETED, or a cancellation from RECEIVED/PREPARING (the cancellation use
-- case itself arrives in ORDER-04). Skipping a step, going backwards or leaving COMPLETED/CANCELLED is rejected here
-- even if application code is wrong or someone updates the row by hand.
CREATE FUNCTION orders_fulfillment_transition_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.fulfillment_status = OLD.fulfillment_status THEN
        RETURN NEW;
    END IF;
    IF (OLD.fulfillment_status, NEW.fulfillment_status) IN (
            ('RECEIVED', 'PREPARING'), ('PREPARING', 'READY'), ('READY', 'SERVED'), ('SERVED', 'COMPLETED'),
            ('RECEIVED', 'CANCELLED'), ('PREPARING', 'CANCELLED')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid fulfillment transition % -> %', OLD.fulfillment_status, NEW.fulfillment_status
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_fulfillment_transition_guard
    BEFORE UPDATE OF fulfillment_status ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_fulfillment_transition_guard();

-- The only payment moves that may ever happen:
--   NOT_REQUESTED > PENDING        a payment attempt started
--   PENDING > PAID | FAILED | CANCELLED
--   FAILED > PENDING               a new attempt after a failure
--   FAILED > CANCELLED             only once the ORDER itself is CANCELLED and nothing was collected; the failed attempt
--                                  is then closed instead of staying FAILED forever
-- PAID and CANCELLED are final. NOT_REQUESTED > CANCELLED is reserved for the order cancellation use case (ORDER-04),
-- which closes the payment axis of an order that never had a payment attempt; no payment event can cause it.
CREATE FUNCTION orders_payment_transition_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.payment_status = OLD.payment_status THEN
        RETURN NEW;
    END IF;
    IF (OLD.payment_status, NEW.payment_status) IN (
            ('NOT_REQUESTED', 'PENDING'), ('PENDING', 'PAID'), ('PENDING', 'FAILED'), ('PENDING', 'CANCELLED'),
            ('FAILED', 'PENDING'), ('NOT_REQUESTED', 'CANCELLED')) THEN
        RETURN NEW;
    END IF;
    IF OLD.payment_status = 'FAILED' AND NEW.payment_status = 'CANCELLED' AND NEW.fulfillment_status = 'CANCELLED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid payment transition % -> %', OLD.payment_status, NEW.payment_status
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_payment_transition_guard
    BEFORE UPDATE OF payment_status ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_payment_transition_guard();
