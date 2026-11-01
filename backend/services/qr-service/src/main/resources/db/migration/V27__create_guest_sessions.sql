CREATE TABLE guest_sessions (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_guest_sessions_token_digest UNIQUE (token_digest),
    CONSTRAINT uq_guest_sessions_restaurant_session_id UNIQUE (restaurant_id, table_session_id, id),
    CONSTRAINT fk_guest_sessions_restaurant_session FOREIGN KEY (restaurant_id, table_session_id)
        REFERENCES table_sessions (restaurant_id, id),
    CONSTRAINT ck_guest_sessions_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guest_sessions_status CHECK (status IN ('ACTIVE', 'ENDED', 'EXPIRED')),
    CONSTRAINT ck_guest_sessions_version CHECK (version >= 0),
    CONSTRAINT ck_guest_sessions_activity CHECK (
        isfinite(joined_at) AND isfinite(last_activity_at) AND last_activity_at >= joined_at),
    CONSTRAINT ck_guest_sessions_expiry CHECK (
        expires_at IS NULL OR (isfinite(expires_at) AND expires_at > joined_at)),
    CONSTRAINT ck_guest_sessions_ended CHECK (
        ended_at IS NULL OR (isfinite(ended_at) AND ended_at >= last_activity_at)),
    CONSTRAINT ck_guest_sessions_lifecycle CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL) OR
        (status IN ('ENDED', 'EXPIRED') AND ended_at IS NOT NULL))
);
