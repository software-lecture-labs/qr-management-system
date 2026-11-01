-- KITCHEN-04 (Kitchen range V81-V90, docs/decisions/migrations.md): inbox of the Order service's cancellation decisions.
--
-- One row per decision event that Kitchen has PROCESSED, so the same event can never be applied twice (the unique key is
-- the sender and its event id). It records what was decided and what Kitchen did with it. Refused events (unknown, malformed,
-- untrusted) are not stored. A row is an audit entry and is never changed or deleted.

CREATE TABLE inbox_events (
    id              uuid         NOT NULL,
    source_service  varchar(40)  NOT NULL,
    event_id        uuid         NOT NULL,   -- the sender's event id
    event_type      varchar(60)  NOT NULL,
    schema_version  integer      NOT NULL,
    restaurant_id   uuid         NOT NULL,
    order_id        uuid         NOT NULL,
    request_id      uuid         NOT NULL,
    order_version   bigint       NOT NULL,   -- the Order service's version of the order after its decision
    decision        varchar(20)  NOT NULL,
    decided_at      timestamptz  NOT NULL,   -- the Order service's time of the decision
    outcome         varchar(30)  NOT NULL,
    correlation_id  varchar(64),
    received_at     timestamptz  NOT NULL,   -- Kitchen's time of processing
    CONSTRAINT pk_inbox_events PRIMARY KEY (id),
    CONSTRAINT uq_inbox_events_source_event UNIQUE (source_service, event_id),
    CONSTRAINT fk_inbox_events_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES kitchen_orders (restaurant_id, order_id),
    CONSTRAINT fk_inbox_events_request FOREIGN KEY (request_id) REFERENCES cancellation_requests (id),
    CONSTRAINT ck_inbox_events_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_inbox_events_outcome CHECK (outcome IN ('CANCELLED', 'REQUEST_REJECTED', 'STALE', 'ALREADY_DECIDED', 'NEEDS_RECONCILIATION')),
    CONSTRAINT ck_inbox_events_version CHECK (order_version >= 0),
    CONSTRAINT ck_inbox_events_schema CHECK (schema_version >= 1)
);

CREATE INDEX ix_inbox_events_request ON inbox_events (request_id);
CREATE INDEX ix_inbox_events_reconciliation ON inbox_events (restaurant_id, received_at) WHERE outcome = 'NEEDS_RECONCILIATION';

CREATE FUNCTION guard_inbox_event_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inbox events are an audit trail and are never changed or deleted' USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER trg_inbox_events_guard
    BEFORE UPDATE OR DELETE ON inbox_events
    FOR EACH ROW EXECUTE FUNCTION guard_inbox_event_change();
