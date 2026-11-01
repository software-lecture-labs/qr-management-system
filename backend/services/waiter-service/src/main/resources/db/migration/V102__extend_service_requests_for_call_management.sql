-- WAITER-02: call management on top of V101 (V101 is already applied and stays untouched).
-- table_id is a logical reference to the QR service, not a foreign key. V101 had no write path, so no
-- production row can lack it; the CHECKs are NOT VALID so that this migration never fails on a stray
-- developer row, while every new or updated row is still enforced.
ALTER TABLE service_requests ADD COLUMN table_id uuid;

ALTER TABLE service_requests
    ADD CONSTRAINT uq_service_requests_tenant UNIQUE (restaurant_id, id),
    ADD CONSTRAINT ck_service_requests_table CHECK (table_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_service_requests_message CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 300) NOT VALID,
    ADD CONSTRAINT ck_service_requests_open CHECK (
        status <> 'OPEN' OR (claimed_by_user_id IS NULL AND resolved_by_user_id IS NULL)) NOT VALID,
    ADD CONSTRAINT ck_service_requests_claimed CHECK (
        status <> 'CLAIMED' OR (claimed_by_user_id IS NOT NULL AND claimed_at IS NOT NULL AND resolved_by_user_id IS NULL)) NOT VALID,
    ADD CONSTRAINT ck_service_requests_resolved CHECK (
        status <> 'RESOLVED' OR (resolved_by_user_id IS NOT NULL AND resolved_at IS NOT NULL)) NOT VALID;

-- One active (OPEN or CLAIMED) call per guest session and type. Calls of different guests of the same table
-- are never merged: the guest session is part of the key.
CREATE UNIQUE INDEX uq_service_requests_active_per_guest
    ON service_requests (restaurant_id, guest_session_id, request_type)
    WHERE status IN ('OPEN', 'CLAIMED');

CREATE INDEX ix_service_requests_listing ON service_requests (restaurant_id, created_at, id);
CREATE INDEX ix_service_requests_table ON service_requests (restaurant_id, table_id, status);
