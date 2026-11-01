CREATE TABLE qr_scan_events (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    qr_code_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    guest_session_id UUID NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    CONSTRAINT uq_qr_scan_events_guest UNIQUE (guest_session_id),
    CONSTRAINT fk_qr_scan_events_qr FOREIGN KEY (restaurant_id, table_id, qr_code_id)
        REFERENCES qr_codes (restaurant_id, table_id, id),
    CONSTRAINT fk_qr_scan_events_session FOREIGN KEY (restaurant_id, table_id, table_session_id)
        REFERENCES table_sessions (restaurant_id, table_id, id),
    CONSTRAINT fk_qr_scan_events_guest FOREIGN KEY (restaurant_id, table_session_id, guest_session_id)
        REFERENCES guest_sessions (restaurant_id, table_session_id, id),
    CONSTRAINT ck_qr_scan_events_time CHECK (isfinite(scanned_at)),
    CONSTRAINT ck_qr_scan_events_outcome CHECK (outcome = 'ACCEPTED')
);

CREATE INDEX ix_qr_scan_events_restaurant_table ON qr_scan_events (restaurant_id, table_id);
CREATE INDEX ix_qr_scan_events_restaurant_session ON qr_scan_events (restaurant_id, table_session_id);
