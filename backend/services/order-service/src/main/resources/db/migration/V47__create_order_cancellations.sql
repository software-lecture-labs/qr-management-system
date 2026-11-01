-- Order Service ORDER-04 / commit 2 (range V41-V50): order cancellation.
--
-- A cancellation is REQUESTED first and DECIDED later (APPLIED or REJECTED); asking and finalising are different facts.
-- An immediate cancellation (the kitchen has not started) is requested and applied in the same transaction. A
-- cancellation that needs the Kitchen's confirmation stays REQUESTED until the trusted Kitchen service answers, so
-- without that answer (for example in production before the Kitchen integration exists) nothing is ever finalised.
--
-- The order snapshot is never rewritten: order_items.cancelled_quantity is the only moving part (the V42 snapshot guard allows exactly that),
-- and from here on the database itself keeps it equal to the sum of the APPLIED cancellations. Money columns are BIGINT
-- minor units and keep apart: the original total (orders.initial_*), what is cancelled, the net after cancellation, the
-- verified amount collected, the remaining debt, the refund the cancellation REQUIRES and the refund that really happened
-- (only the Payment Service may ever confirm that; the Order Service never writes refunded_minor).
--
-- WHO acts is stored with its true kind: GUEST (a customer's guest session), STAFF (a verified staff member: waiter or
-- admin) or SERVICE (a verified calling service; today only the Kitchen service may ask for a cancellation). A service is
-- never stored as a staff member. The staff member a service CLAIMS to act for is only information: it lives apart in
-- order_cancellations.reported_staff_id, is written once with the request, is never an author, and is never used to
-- authorise anything or to decide that somebody "already did" something.
--
-- D-13 (decision of the Order Service owner): this file was amended in place, with explicit permission, BEFORE it was ever
-- pushed or applied to a persistent database (no new migration number was used). It must not be edited again once shared.

CREATE TABLE order_cancellations (
    id                            uuid          NOT NULL,
    restaurant_id                 uuid          NOT NULL,
    order_id                      uuid          NOT NULL,
    scope                         varchar(10)   NOT NULL,
    status                        varchar(12)   NOT NULL,
    requested_by_type             varchar(10)   NOT NULL,
    requested_by_id               varchar(100)  NOT NULL,
    requested_by_role             varchar(30),
    reported_staff_id             uuid,
    requested_at                  timestamptz   NOT NULL,
    kitchen_confirmation_required boolean       NOT NULL,
    fulfillment_status_at_request varchar(20)   NOT NULL,
    payment_status_at_request     varchar(20)   NOT NULL,
    decision_source               varchar(10),
    decided_by_type               varchar(10),
    decided_by_id                 varchar(100),
    decided_by_role               varchar(30),
    decided_at                    timestamptz,
    rejection_code                varchar(40),
    rejection_note                varchar(300),
    currency                      varchar(3)    NOT NULL,
    previous_due_minor            bigint        NOT NULL,
    cancelled_gross_minor         bigint        NOT NULL,
    cancelled_net_minor           bigint        NOT NULL,
    cancelled_vat_minor           bigint        NOT NULL,
    new_due_minor                 bigint        NOT NULL,
    collected_minor               bigint        NOT NULL,
    refund_required_minor         bigint        NOT NULL DEFAULT 0,
    refunded_minor                bigint        NOT NULL DEFAULT 0,
    staff_reason_added_at         timestamptz,
    staff_reason_added_by         varchar(100),
    version                       bigint        NOT NULL DEFAULT 0,
    created_at                    timestamptz   NOT NULL,
    updated_at                    timestamptz   NOT NULL,
    CONSTRAINT pk_order_cancellations PRIMARY KEY (id),
    CONSTRAINT uq_order_cancellations_restaurant_id_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_order_cancellations_order FOREIGN KEY (restaurant_id, order_id) REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_order_cancellations_scope CHECK (scope IN ('FULL', 'PARTIAL')),
    CONSTRAINT ck_order_cancellations_status CHECK (status IN ('REQUESTED', 'APPLIED', 'REJECTED')),
    CONSTRAINT ck_order_cancellations_requester CHECK (requested_by_type IN ('GUEST', 'STAFF', 'SERVICE')),
    -- a service requester is the verified Kitchen service: its id and role are its service name, nothing else
    -- (IS TRUE: a missing role must fail the check, not pass it as "unknown")
    CONSTRAINT ck_order_cancellations_service_requester CHECK (requested_by_type <> 'SERVICE'
        OR (requested_by_id = 'KITCHEN' AND requested_by_role = 'KITCHEN') IS TRUE),
    -- the staff member a service says it acted for is a claim that exists only next to a service request
    CONSTRAINT ck_order_cancellations_reported_staff CHECK (reported_staff_id IS NULL OR requested_by_type = 'SERVICE'),
    CONSTRAINT ck_order_cancellations_decision_source CHECK (decision_source IS NULL
        OR decision_source IN ('AUTO', 'KITCHEN', 'SYSTEM')),
    CONSTRAINT ck_order_cancellations_decider CHECK (decided_by_type IS NULL OR decided_by_type IN ('SYSTEM', 'SERVICE')),
    -- decided if and only if there is a decision time, a source and a decider; rejected if and only if there is a code
    CONSTRAINT ck_order_cancellations_decision CHECK (
        (status = 'REQUESTED') = (decided_at IS NULL)
        AND (decided_at IS NULL) = (decision_source IS NULL)
        AND (decided_at IS NULL) = (decided_by_type IS NULL)
        AND (status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_order_cancellations_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_cancellations_amounts CHECK (
        previous_due_minor >= 0 AND cancelled_gross_minor >= 0 AND cancelled_net_minor >= 0 AND cancelled_vat_minor >= 0
        AND new_due_minor >= 0 AND collected_minor >= 0 AND refund_required_minor >= 0 AND refunded_minor >= 0
        AND cancelled_net_minor + cancelled_vat_minor = cancelled_gross_minor
        AND new_due_minor <= previous_due_minor
        AND refund_required_minor <= collected_minor
        AND refunded_minor <= refund_required_minor)
);

-- One open request per order: while a cancellation waits for the kitchen no other can be started.
CREATE UNIQUE INDEX uq_order_cancellations_one_open_per_order ON order_cancellations (order_id)
    WHERE status = 'REQUESTED';
CREATE INDEX ix_order_cancellations_order ON order_cancellations (restaurant_id, order_id, requested_at);
CREATE INDEX ix_order_cancellations_report ON order_cancellations (restaurant_id, requested_at DESC);
CREATE INDEX ix_order_cancellations_status ON order_cancellations (restaurant_id, status, requested_at DESC);

-- What a cancellation cancels: which order lines and how many units, priced from the immutable order snapshot.
CREATE TABLE order_cancellation_items (
    id                    uuid          NOT NULL,
    restaurant_id         uuid          NOT NULL,
    cancellation_id       uuid          NOT NULL,
    order_item_id         uuid          NOT NULL,
    product_id            uuid          NOT NULL,
    product_name_snapshot varchar(200)  NOT NULL,
    quantity              integer       NOT NULL,
    unit_gross_minor      bigint        NOT NULL,
    tax_rate_bps          integer       NOT NULL,
    gross_minor           bigint        NOT NULL,
    net_minor             bigint        NOT NULL,
    vat_minor             bigint        NOT NULL,
    CONSTRAINT pk_order_cancellation_items PRIMARY KEY (id),
    CONSTRAINT uq_order_cancellation_items_line UNIQUE (cancellation_id, order_item_id),
    CONSTRAINT fk_order_cancellation_items_cancellation FOREIGN KEY (restaurant_id, cancellation_id)
        REFERENCES order_cancellations (restaurant_id, id),
    CONSTRAINT fk_order_cancellation_items_item FOREIGN KEY (restaurant_id, order_item_id)
        REFERENCES order_items (restaurant_id, id),
    CONSTRAINT ck_order_cancellation_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_cancellation_items_amounts CHECK (unit_gross_minor >= 0 AND gross_minor >= 0 AND net_minor >= 0
        AND vat_minor >= 0 AND net_minor + vat_minor = gross_minor AND gross_minor = unit_gross_minor * quantity)
);

CREATE INDEX ix_order_cancellation_items_item ON order_cancellation_items (order_item_id);

-- Why: one row per reason, append-only. The customer's reasons (GUEST) are written with the request; when the customer
-- gave none, a waiter completes it later (STAFF) and never overwrites anything. reason_code is the central catalog
-- (CancellationReasonCode in the code, mirrored here and checked by a test); reason_text is plain free text.
CREATE TABLE order_cancellation_reasons (
    seq             bigint        GENERATED ALWAYS AS IDENTITY,
    id              uuid          NOT NULL,
    restaurant_id   uuid          NOT NULL,
    cancellation_id uuid          NOT NULL,
    source          varchar(10)   NOT NULL,
    reason_code     varchar(40),
    reason_text     varchar(300),
    added_by_type   varchar(10)   NOT NULL,
    added_by_id     varchar(100)  NOT NULL,
    added_by_role   varchar(30),
    added_at        timestamptz   NOT NULL,
    CONSTRAINT pk_order_cancellation_reasons PRIMARY KEY (id),
    CONSTRAINT uq_order_cancellation_reasons_seq UNIQUE (seq),
    CONSTRAINT fk_order_cancellation_reasons_cancellation FOREIGN KEY (restaurant_id, cancellation_id)
        REFERENCES order_cancellations (restaurant_id, id),
    CONSTRAINT ck_order_cancellation_reasons_source CHECK (source IN ('GUEST', 'STAFF', 'SERVICE')),
    CONSTRAINT ck_order_cancellation_reasons_actor CHECK (added_by_type = source),
    -- a reason of a service is the verified Kitchen service's own
    CONSTRAINT ck_order_cancellation_reasons_service CHECK (source <> 'SERVICE'
        OR (added_by_id = 'KITCHEN' AND added_by_role = 'KITCHEN') IS TRUE),
    CONSTRAINT ck_order_cancellation_reasons_code CHECK (reason_code IS NULL OR reason_code IN
        ('CHANGED_MIND', 'ORDERED_BY_MISTAKE', 'WAIT_TOO_LONG', 'ITEM_UNAVAILABLE', 'KITCHEN_ERROR', 'OTHER')),
    CONSTRAINT ck_order_cancellation_reasons_content CHECK (reason_code IS NOT NULL OR reason_text IS NOT NULL),
    CONSTRAINT ck_order_cancellation_reasons_text CHECK (reason_text IS NULL OR length(btrim(reason_text)) > 0)
);

-- a code appears once per cancellation, and each source leaves at most one free text
CREATE UNIQUE INDEX uq_order_cancellation_reasons_code ON order_cancellation_reasons (cancellation_id, reason_code)
    WHERE reason_code IS NOT NULL;
CREATE UNIQUE INDEX uq_order_cancellation_reasons_text ON order_cancellation_reasons (cancellation_id, source)
    WHERE reason_code IS NULL;
CREATE INDEX ix_order_cancellation_reasons_report ON order_cancellation_reasons (restaurant_id, reason_code);
CREATE INDEX ix_order_cancellation_reasons_cancellation ON order_cancellation_reasons (cancellation_id, seq);

-- ---- database guards ---------------------------------------------------------------------------------------------

-- A cancellation is never deleted. Who asked, when, and the state of the order at that moment never change. A request
-- may be decided once (REQUESTED > APPLIED | REJECTED) and is final afterwards, except that a waiter's reason
-- completion is set once and the Payment Service may later raise refunded_minor (never lower it).
CREATE FUNCTION order_cancellations_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'order cancellations are never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
        OR NEW.requested_by_id IS DISTINCT FROM OLD.requested_by_id
        OR NEW.requested_by_role IS DISTINCT FROM OLD.requested_by_role
        OR NEW.reported_staff_id IS DISTINCT FROM OLD.reported_staff_id
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.kitchen_confirmation_required IS DISTINCT FROM OLD.kitchen_confirmation_required
        OR NEW.fulfillment_status_at_request IS DISTINCT FROM OLD.fulfillment_status_at_request
        OR NEW.payment_status_at_request IS DISTINCT FROM OLD.payment_status_at_request
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'order cancellation % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'REQUESTED' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.decision_source IS DISTINCT FROM OLD.decision_source
        OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
        OR NEW.decided_by_id IS DISTINCT FROM OLD.decided_by_id
        OR NEW.decided_by_role IS DISTINCT FROM OLD.decided_by_role
        OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
        OR NEW.rejection_code IS DISTINCT FROM OLD.rejection_code
        OR NEW.rejection_note IS DISTINCT FROM OLD.rejection_note
        OR NEW.previous_due_minor IS DISTINCT FROM OLD.previous_due_minor
        OR NEW.cancelled_gross_minor IS DISTINCT FROM OLD.cancelled_gross_minor
        OR NEW.cancelled_net_minor IS DISTINCT FROM OLD.cancelled_net_minor
        OR NEW.cancelled_vat_minor IS DISTINCT FROM OLD.cancelled_vat_minor
        OR NEW.new_due_minor IS DISTINCT FROM OLD.new_due_minor
        OR NEW.collected_minor IS DISTINCT FROM OLD.collected_minor
        OR NEW.refund_required_minor IS DISTINCT FROM OLD.refund_required_minor) THEN
        RAISE EXCEPTION 'order cancellation % is already %, its decision is final', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.refunded_minor < OLD.refunded_minor THEN
        RAISE EXCEPTION 'order cancellation %: a refund that happened cannot be taken back', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.staff_reason_added_at IS NOT NULL AND (NEW.staff_reason_added_at IS DISTINCT FROM OLD.staff_reason_added_at
        OR NEW.staff_reason_added_by IS DISTINCT FROM OLD.staff_reason_added_by) THEN
        RAISE EXCEPTION 'order cancellation %: the reason has already been completed', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_cancellations_guard
    BEFORE UPDATE OR DELETE ON order_cancellations
    FOR EACH ROW EXECUTE FUNCTION order_cancellations_guard();

-- Cancellation lines are written once. A line belongs to the cancellation's own order, carries exactly the price of the
-- immutable order line, and cannot ask for more units than were ordered.
CREATE FUNCTION order_cancellation_items_guard() RETURNS trigger AS $$
DECLARE
    line RECORD;
    cancelled_order uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'order cancellation lines are append-only' USING ERRCODE = 'check_violation';
    END IF;
    SELECT order_id, unit_gross_minor, tax_rate_bps, ordered_quantity, product_id INTO line
        FROM order_items WHERE id = NEW.order_item_id AND restaurant_id = NEW.restaurant_id;
    SELECT order_id INTO cancelled_order FROM order_cancellations
        WHERE id = NEW.cancellation_id AND restaurant_id = NEW.restaurant_id;
    IF line.order_id IS DISTINCT FROM cancelled_order THEN
        RAISE EXCEPTION 'order line % does not belong to the order of cancellation %', NEW.order_item_id,
            NEW.cancellation_id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.unit_gross_minor <> line.unit_gross_minor OR NEW.tax_rate_bps <> line.tax_rate_bps
        OR NEW.product_id <> line.product_id OR NEW.quantity > line.ordered_quantity THEN
        RAISE EXCEPTION 'cancellation line does not match the order snapshot of line %', NEW.order_item_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_cancellation_items_guard
    BEFORE INSERT OR UPDATE OR DELETE ON order_cancellation_items
    FOR EACH ROW EXECUTE FUNCTION order_cancellation_items_guard();

-- Reasons are append-only. A waiter's (or admin's) completion is only for a cancellation the customer gave no reason for,
-- the customer's reasons can never be added after a staff member's or a service's, and the reasons of one cancellation
-- never mix a staff member's with a service's.
CREATE FUNCTION order_cancellation_reasons_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'order cancellation reasons are append-only' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.source IN ('STAFF', 'SERVICE') AND EXISTS (SELECT 1 FROM order_cancellation_reasons r
            WHERE r.cancellation_id = NEW.cancellation_id AND r.source = 'GUEST') THEN
        RAISE EXCEPTION 'cancellation %: the customer already gave a reason, a waiter cannot add one', NEW.cancellation_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.source = 'GUEST' AND EXISTS (SELECT 1 FROM order_cancellation_reasons r
            WHERE r.cancellation_id = NEW.cancellation_id AND r.source IN ('STAFF', 'SERVICE')) THEN
        RAISE EXCEPTION 'cancellation %: a waiter or the kitchen already completed the reason', NEW.cancellation_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- a staff member's reason and a service's reason are never mixed in one cancellation
    IF NEW.source IN ('STAFF', 'SERVICE') AND EXISTS (SELECT 1 FROM order_cancellation_reasons r
            WHERE r.cancellation_id = NEW.cancellation_id AND r.source IN ('STAFF', 'SERVICE')
              AND r.source <> NEW.source) THEN
        RAISE EXCEPTION 'cancellation %: reasons of a staff member and of a service are never mixed', NEW.cancellation_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_cancellation_reasons_guard
    BEFORE INSERT OR UPDATE OR DELETE ON order_cancellation_reasons
    FOR EACH ROW EXECUTE FUNCTION order_cancellation_reasons_guard();

-- order_items.cancelled_quantity always equals the units of the APPLIED cancellations of that line. Checked at commit, so
-- the cancellation rows and the counter may be written in either order inside one transaction, but a counter that was
-- changed by hand, a cancellation that was applied without moving it, or a double deduction can never be committed.
CREATE FUNCTION order_item_cancelled_quantity_matches(item uuid) RETURNS void AS $$
DECLARE
    actual integer;
    expected bigint;
BEGIN
    SELECT cancelled_quantity INTO actual FROM order_items WHERE id = item;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT coalesce(sum(i.quantity), 0) INTO expected FROM order_cancellation_items i
        JOIN order_cancellations c ON c.id = i.cancellation_id
        WHERE i.order_item_id = item AND c.status = 'APPLIED';
    IF actual <> expected THEN
        RAISE EXCEPTION 'order line %: cancelled_quantity % does not match the % units of its applied cancellations',
            item, actual, expected USING ERRCODE = 'check_violation';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION order_items_cancelled_quantity_check() RETURNS trigger AS $$
BEGIN
    PERFORM order_item_cancelled_quantity_matches(NEW.id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_order_items_cancelled_quantity
    AFTER INSERT OR UPDATE OF cancelled_quantity ON order_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION order_items_cancelled_quantity_check();

CREATE FUNCTION order_cancellation_items_cancelled_quantity_check() RETURNS trigger AS $$
BEGIN
    PERFORM order_item_cancelled_quantity_matches(NEW.order_item_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_order_cancellation_items_cancelled_quantity
    AFTER INSERT ON order_cancellation_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION order_cancellation_items_cancelled_quantity_check();

CREATE FUNCTION order_cancellations_cancelled_quantity_check() RETURNS trigger AS $$
DECLARE
    item uuid;
BEGIN
    FOR item IN SELECT order_item_id FROM order_cancellation_items WHERE cancellation_id = NEW.id LOOP
        PERFORM order_item_cancelled_quantity_matches(item);
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_order_cancellations_cancelled_quantity
    AFTER UPDATE OF status ON order_cancellations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION order_cancellations_cancelled_quantity_check();
