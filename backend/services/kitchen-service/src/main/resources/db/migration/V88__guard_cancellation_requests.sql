-- KITCHEN-04 (Kitchen range V81-V90, docs/decisions/migrations.md): stock reference rules and an immutable audit trail
-- for cancellation requests. V87 stays untouched.
--
-- 1. Stock reference: a product / order line is only recorded for an OUT_OF_STOCK request, and a named line must be a
--    line of the same restaurant. That the line belongs to THIS order and carries THIS product is checked by the
--    application against the order's own snapshot (the database cannot express it without another table).
-- 2. Audit trail: what the kitchen asked (who, what, why, when) can never be changed afterwards, a decided request is
--    final, and a request is never deleted. Only the first and only decision can be added to a REQUESTED request.

ALTER TABLE cancellation_requests
    ADD CONSTRAINT fk_cancellation_requests_item FOREIGN KEY (restaurant_id, order_item_id)
        REFERENCES kitchen_order_items (restaurant_id, order_item_id);

ALTER TABLE cancellation_requests
    ADD CONSTRAINT ck_cancellation_requests_stock_reference
        CHECK (reason_code = 'OUT_OF_STOCK' OR (product_id IS NULL AND order_item_id IS NULL));

CREATE FUNCTION guard_cancellation_request_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.command_id IS DISTINCT FROM OLD.command_id
        OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
        OR NEW.reason_text IS DISTINCT FROM OLD.reason_text
        OR NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.order_item_id IS DISTINCT FROM OLD.order_item_id
        OR NEW.requested_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'what a cancellation request asked for is immutable' USING ERRCODE = '23000';
    END IF;
    IF OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'a decided cancellation request is final' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cancellation_requests_guard_update
    BEFORE UPDATE ON cancellation_requests
    FOR EACH ROW EXECUTE FUNCTION guard_cancellation_request_update();

CREATE FUNCTION guard_cancellation_request_delete() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'cancellation requests are an audit trail and are never deleted' USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER trg_cancellation_requests_guard_delete
    BEFORE DELETE ON cancellation_requests
    FOR EACH ROW EXECUTE FUNCTION guard_cancellation_request_delete();
