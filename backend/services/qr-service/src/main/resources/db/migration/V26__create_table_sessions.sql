CREATE TABLE table_sessions (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    idle_expires_at TIMESTAMPTZ,
    closing_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    closed_by_user_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_table_sessions_restaurant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_table_sessions_restaurant_table_id UNIQUE (restaurant_id, table_id, id),
    CONSTRAINT fk_table_sessions_restaurant_table FOREIGN KEY (restaurant_id, table_id)
        REFERENCES dining_tables (restaurant_id, id),
    CONSTRAINT ck_table_sessions_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED', 'EXPIRED')),
    CONSTRAINT ck_table_sessions_version CHECK (version >= 0),
    CONSTRAINT ck_table_sessions_activity CHECK (
        isfinite(opened_at) AND isfinite(last_activity_at) AND last_activity_at >= opened_at),
    CONSTRAINT ck_table_sessions_idle CHECK (
        idle_expires_at IS NULL OR (isfinite(idle_expires_at) AND idle_expires_at > last_activity_at)),
    CONSTRAINT ck_table_sessions_closing CHECK (
        closing_at IS NULL OR (isfinite(closing_at) AND closing_at >= opened_at)),
    CONSTRAINT ck_table_sessions_closed CHECK (
        closed_at IS NULL OR (isfinite(closed_at) AND closed_at >= last_activity_at
            AND (closing_at IS NULL OR closed_at >= closing_at))),
    CONSTRAINT ck_table_sessions_lifecycle CHECK (
        (status = 'OPEN' AND closing_at IS NULL AND closed_at IS NULL) OR
        (status = 'CLOSING' AND closing_at IS NOT NULL AND closed_at IS NULL) OR
        (status IN ('CLOSED', 'EXPIRED') AND closed_at IS NOT NULL)),
    CONSTRAINT ck_table_sessions_actor CHECK (closed_by_user_id IS NULL OR closed_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_table_sessions_live_table
    ON table_sessions (restaurant_id, table_id) WHERE status IN ('OPEN', 'CLOSING');
