-- Preserve the immutable request and local receipt across retries/restarts; no financial projection is created.
CREATE FUNCTION guard_session_transition_history() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Lifecycle history cannot be deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.stage <> 'REQUESTED' OR NEW.result_json IS NOT NULL THEN
            RAISE EXCEPTION 'Lifecycle must start requested' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.table_session_id IS DISTINCT FROM OLD.table_session_id OR NEW.request_json IS DISTINCT FROM OLD.request_json
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR (OLD.result_json IS NOT NULL AND NEW.result_json IS DISTINCT FROM OLD.result_json)
        OR NEW.attempts < OLD.attempts
        OR (OLD.last_attempt_at IS NOT NULL AND (NEW.last_attempt_at IS NULL OR NEW.last_attempt_at < OLD.last_attempt_at)) THEN
        RAISE EXCEPTION 'Lifecycle identity/receipt is immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.stage <> OLD.stage AND NOT (
        (OLD.stage = 'REQUESTED' AND NEW.stage IN ('APPLIED', 'ABORTING')) OR
        (OLD.stage = 'APPLIED' AND NEW.stage = 'COMPLETED') OR
        (OLD.stage = 'ABORTING' AND NEW.stage = 'ABORTED')) THEN
        RAISE EXCEPTION 'Invalid lifecycle transition' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_session_transition_history BEFORE INSERT OR UPDATE OR DELETE ON session_transition_operations
    FOR EACH ROW EXECUTE FUNCTION guard_session_transition_history();
