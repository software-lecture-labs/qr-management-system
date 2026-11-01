-- WAITER-02: audit trail, idempotency records and the durable Notification delivery queue (docs/database.md
-- section 13 pattern, D-10 first stage: local outbox plus internal HTTP, no broker). Everything lives in the
-- Waiter database; the composite (restaurant_id, id) foreign keys keep children inside the call's tenant.

-- Immutable record of every business change of a call. payload is the exact JSON that is sent to Notification
-- (a state snapshot at call_version); it never contains a guest token or the guest's free text.
CREATE TABLE service_call_events (
    id                 uuid        NOT NULL,
    restaurant_id      uuid        NOT NULL,
    service_request_id uuid        NOT NULL,
    call_version       bigint      NOT NULL,
    event_type         varchar(20) NOT NULL,
    from_status        varchar(20),
    to_status          varchar(20) NOT NULL,
    actor_type         varchar(10) NOT NULL,
    actor_id           uuid        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    payload            jsonb       NOT NULL,
    CONSTRAINT pk_service_call_events PRIMARY KEY (id),
    CONSTRAINT uq_service_call_events_version UNIQUE (service_request_id, call_version),
    CONSTRAINT fk_service_call_events_call FOREIGN KEY (restaurant_id, service_request_id)
        REFERENCES service_requests (restaurant_id, id),
    CONSTRAINT ck_service_call_events_type CHECK (event_type IN ('CREATED', 'CLAIMED', 'RESOLVED')),
    CONSTRAINT ck_service_call_events_actor CHECK (actor_type IN ('GUEST', 'STAFF')),
    CONSTRAINT ck_service_call_events_version CHECK (call_version >= 0)
);

-- Delivery of one event to Notification. PENDING rows survive restarts; DEAD is the visible terminal failure.
CREATE TABLE notification_deliveries (
    event_id           uuid        NOT NULL,
    restaurant_id      uuid        NOT NULL,
    service_request_id uuid        NOT NULL,
    call_version       bigint      NOT NULL,
    status             varchar(12) NOT NULL,
    attempt_count      integer     NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL,
    last_attempt_at    timestamptz,
    last_error_code    varchar(40),
    delivered_at       timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT pk_notification_deliveries PRIMARY KEY (event_id),
    CONSTRAINT fk_notification_deliveries_event FOREIGN KEY (event_id) REFERENCES service_call_events (id),
    CONSTRAINT fk_notification_deliveries_call FOREIGN KEY (restaurant_id, service_request_id)
        REFERENCES service_requests (restaurant_id, id),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'DELIVERED', 'SUPERSEDED', 'DEAD')),
    CONSTRAINT ck_notification_deliveries_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_deliveries_delivered CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL)
);

CREATE INDEX ix_notification_deliveries_due ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_deliveries_call ON notification_deliveries (service_request_id, call_version);

-- Same key + same request digest repeats the first result; the same key with another digest is a conflict.
-- Only a SHA-256 digest of the request is kept, never the request body.
CREATE TABLE idempotency_records (
    id                 uuid         NOT NULL,
    restaurant_id      uuid         NOT NULL,
    actor_scope        varchar(80)  NOT NULL,
    operation          varchar(20)  NOT NULL,
    idempotency_key    varchar(120) NOT NULL,
    request_digest     char(64)     NOT NULL,
    service_request_id uuid         NOT NULL,
    response_status    smallint     NOT NULL,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_records_key UNIQUE (restaurant_id, actor_scope, operation, idempotency_key),
    CONSTRAINT fk_idempotency_records_call FOREIGN KEY (restaurant_id, service_request_id)
        REFERENCES service_requests (restaurant_id, id),
    CONSTRAINT ck_idempotency_records_operation CHECK (operation IN ('CREATE', 'CLAIM', 'RESOLVE')),
    CONSTRAINT ck_idempotency_records_status CHECK (response_status IN (200, 201))
);
