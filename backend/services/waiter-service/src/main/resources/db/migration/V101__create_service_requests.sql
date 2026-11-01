-- WAITER-01: minimal local call model (docs/database.md section 9).
-- Read-only in WAITER-01; creation, claim and resolve mutations belong to WAITER-02.
-- table_session_id and guest_session_id are logical references to the QR service, not foreign keys.
CREATE TABLE service_requests (
    id                  uuid        NOT NULL,
    restaurant_id       uuid        NOT NULL,
    table_session_id    uuid        NOT NULL,
    guest_session_id    uuid        NOT NULL,
    request_type        varchar(20) NOT NULL,
    message             text,
    status              varchar(20) NOT NULL,
    claimed_by_user_id  uuid,
    claimed_at          timestamptz,
    resolved_by_user_id uuid,
    resolved_at         timestamptz,
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_service_requests PRIMARY KEY (id),
    CONSTRAINT ck_service_requests_type CHECK (request_type IN ('WAITER', 'HELP', 'BILL')),
    CONSTRAINT ck_service_requests_status CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT ck_service_requests_version CHECK (version >= 0)
);

CREATE INDEX ix_service_requests_session
    ON service_requests (restaurant_id, table_session_id, status);
