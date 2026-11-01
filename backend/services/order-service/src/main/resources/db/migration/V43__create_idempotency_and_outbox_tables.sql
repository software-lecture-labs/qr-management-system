-- Order Service ORDER-02: technical tables for idempotent commands and the transactional outbox.
-- No broker or dispatcher exists yet: outbox rows are written atomically with the business change and wait as PENDING.

CREATE TABLE idempotency_records (
    id               uuid          NOT NULL,
    restaurant_id    uuid          NOT NULL,
    actor_scope      varchar(100)  NOT NULL,
    operation        varchar(60)   NOT NULL,
    idempotency_key  varchar(120)  NOT NULL,
    request_digest   varchar(64)   NOT NULL,
    resource_id      uuid          NOT NULL,
    response_status  integer       NOT NULL,
    created_at       timestamptz   NOT NULL,
    -- retention: expired COMPLETED records can be purged safely; IN_PROGRESS rows are never purged
    expires_at       timestamptz   NOT NULL,
    status           varchar(20)   NOT NULL DEFAULT 'COMPLETED',
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    -- the same key from the same actor for the same operation can only ever be used once
    CONSTRAINT uq_idempotency_scope_key UNIQUE (restaurant_id, actor_scope, operation, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- the purge job selects COMPLETED rows whose expires_at has passed
CREATE INDEX ix_idempotency_cleanup ON idempotency_records (status, expires_at);

CREATE TABLE outbox_events (
    id                uuid          NOT NULL,
    restaurant_id     uuid          NOT NULL,
    aggregate_type    varchar(40)   NOT NULL,
    aggregate_id      uuid          NOT NULL,
    aggregate_version bigint        NOT NULL,
    event_type        varchar(80)   NOT NULL,
    schema_version    integer       NOT NULL,
    correlation_id    varchar(64),
    payload           jsonb         NOT NULL,
    occurred_at       timestamptz   NOT NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_events_schema CHECK (schema_version > 0)
);

CREATE INDEX ix_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id, occurred_at);

-- One delivery row per destination so every consumer is tracked and retried independently.
CREATE TABLE outbox_deliveries (
    id               uuid          NOT NULL,
    event_id         uuid          NOT NULL,
    destination      varchar(40)   NOT NULL,
    status           varchar(20)   NOT NULL,
    attempt_count    integer       NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    delivered_at     timestamptz,
    last_error_code  varchar(80),
    CONSTRAINT pk_outbox_deliveries PRIMARY KEY (id),
    CONSTRAINT uq_outbox_deliveries_destination UNIQUE (event_id, destination),
    CONSTRAINT fk_outbox_deliveries_event FOREIGN KEY (event_id) REFERENCES outbox_events (id),
    CONSTRAINT ck_outbox_deliveries_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_outbox_deliveries_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_deliveries_pending ON outbox_deliveries (status, next_attempt_at);
