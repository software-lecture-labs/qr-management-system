-- QR/Payment owner approved the additional QR V121-V130 allocation and local branch reset on 2026-10-31.
-- This receipt stores source acknowledgement/scope drift only, not orders, balances, payments or credentials.
CREATE TABLE session_source_confirmations (
    operation_id UUID PRIMARY KEY REFERENCES session_transition_operations (id),
    restaurant_id UUID NOT NULL,
    confirmation_json TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_source_confirmation_json CHECK (jsonb_typeof(confirmation_json::jsonb) = 'object'),
    CONSTRAINT ck_source_confirmation_time CHECK (isfinite(received_at))
);

CREATE INDEX ix_source_confirmations_tenant ON session_source_confirmations (restaurant_id, operation_id);

CREATE FUNCTION guard_session_source_confirmation() RETURNS trigger AS $$
DECLARE
    operation session_transition_operations%ROWTYPE;
    proof JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Source confirmation is immutable' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO operation FROM session_transition_operations WHERE id = NEW.operation_id FOR UPDATE;
    proof := NEW.confirmation_json::jsonb;
    IF NOT FOUND OR operation.stage <> 'APPLIED' OR operation.restaurant_id <> NEW.restaurant_id
        OR (proof->>'operationId') IS DISTINCT FROM NEW.operation_id::text
        OR (proof->>'restaurantId') IS DISTINCT FROM NEW.restaurant_id::text
        OR (proof->>'sessionId') IS DISTINCT FROM operation.table_session_id::text
        OR (proof->>'commandDigest') IS DISTINCT FROM operation.request_digest
        OR (proof->>'state') IS DISTINCT FROM 'COMPLETED'
        OR (proof->>'complete') IS DISTINCT FROM 'true'
        OR (proof->>'effect') IS DISTINCT FROM (operation.result_json::jsonb->>'effect') THEN
        RAISE EXCEPTION 'Source confirmation does not match applied operation' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_session_source_confirmation BEFORE INSERT OR UPDATE OR DELETE ON session_source_confirmations
    FOR EACH ROW EXECUTE FUNCTION guard_session_source_confirmation();
