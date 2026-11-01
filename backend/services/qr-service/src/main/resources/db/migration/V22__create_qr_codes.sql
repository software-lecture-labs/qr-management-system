CREATE TABLE qr_codes (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    encrypted_payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_qr_codes_restaurant_table FOREIGN KEY (restaurant_id, table_id)
        REFERENCES dining_tables (restaurant_id, id),
    CONSTRAINT uq_qr_codes_token_digest UNIQUE (token_digest),
    CONSTRAINT uq_qr_codes_restaurant_table_id UNIQUE (restaurant_id, table_id, id),
    CONSTRAINT ck_qr_codes_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_qr_codes_payload CHECK (encrypted_payload ~ '^v1:[A-Za-z0-9_-]{95}$'),
    CONSTRAINT ck_qr_codes_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    CONSTRAINT ck_qr_codes_revocation CHECK ((status IN ('ACTIVE', 'INACTIVE') AND revoked_at IS NULL) OR
        (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= issued_at)),
    CONSTRAINT ck_qr_codes_expiration CHECK (expires_at IS NULL OR (isfinite(expires_at) AND expires_at > issued_at)),
    CONSTRAINT ck_qr_codes_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_qr_codes_active_table
    ON qr_codes (restaurant_id, table_id) WHERE status = 'ACTIVE';
