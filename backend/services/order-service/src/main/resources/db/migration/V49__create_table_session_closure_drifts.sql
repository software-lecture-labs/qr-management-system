-- Order Service ADR-QR-001 (K9; range V41-V50): a closure the QR Service completed although the orders of the table
-- session no longer matched what the closure was prepared on.
--
-- The QR Service confirms CLOSED only after it has closed the table locally, and it never reopens a closed table by
-- itself. The Order Service therefore always accepts CLOSED for a PREPARED closure (a participant that said "ready"
-- cannot refuse the final decision) and records here that the facts had moved, so that people can reconcile it. Money
-- reports that arrive after the preparation (for example a second collection) are the realistic cause: they can never
-- be rejected, only recorded.
--
-- One row per operation at most (UNIQUE), written in the same transaction that completes the closure, never updated or
-- deleted. restaurant_id and table_session_id repeat the closure's values so the rows can be read per tenant.

CREATE TABLE table_session_closure_drifts (
    id                      uuid          NOT NULL,
    operation_id            uuid          NOT NULL,
    restaurant_id           uuid          NOT NULL,
    table_session_id        uuid          NOT NULL,
    fingerprint_at_prepare  varchar(64)   NOT NULL,
    fingerprint_at_confirm  varchar(64)   NOT NULL,
    outcome_at_confirm      varchar(12)   NOT NULL,
    blockers                varchar(300)  NOT NULL DEFAULT '',
    detected_at             timestamptz   NOT NULL,
    CONSTRAINT pk_table_session_closure_drifts PRIMARY KEY (id),
    CONSTRAINT uq_table_session_closure_drifts_operation UNIQUE (operation_id),
    CONSTRAINT fk_table_session_closure_drifts_closure FOREIGN KEY (operation_id)
        REFERENCES table_session_closures (operation_id),
    CONSTRAINT ck_table_session_closure_drifts_fingerprints CHECK (
        fingerprint_at_prepare ~ '^[0-9a-f]{64}$' AND fingerprint_at_confirm ~ '^[0-9a-f]{64}$'
        AND fingerprint_at_prepare <> fingerprint_at_confirm),
    CONSTRAINT ck_table_session_closure_drifts_outcome CHECK (
        outcome_at_confirm IN ('ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN'))
);

CREATE INDEX ix_table_session_closure_drifts_restaurant
    ON table_session_closure_drifts (restaurant_id, detected_at);

CREATE FUNCTION table_session_closure_drifts_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table session closure drifts are append-only' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_session_closure_drifts_append_only
    BEFORE UPDATE OR DELETE ON table_session_closure_drifts
    FOR EACH ROW EXECUTE FUNCTION table_session_closure_drifts_append_only();
