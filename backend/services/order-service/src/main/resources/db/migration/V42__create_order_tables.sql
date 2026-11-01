-- Order Service ORDER-02: immutable order snapshots (Flyway range V41-V50, docs/decisions/migrations.md).
-- Money is integer minor units (kurus). restaurant_id/table_*/guest_* are logical REFs to other services (no cross-DB FK).
-- Snapshot columns are written once at checkout and never updated; later phases only change status/due/cancelled fields.

CREATE TABLE orders (
    id                 uuid         NOT NULL,
    restaurant_id      uuid         NOT NULL,
    order_no           varchar(40)  NOT NULL,
    cart_id            uuid         NOT NULL,
    table_id           uuid         NOT NULL,
    table_session_id   uuid         NOT NULL,
    guest_session_id   uuid         NOT NULL,
    fulfillment_status varchar(20)  NOT NULL,
    payment_status     varchar(20)  NOT NULL,
    currency           varchar(3)   NOT NULL,
    initial_net_minor  bigint       NOT NULL,
    initial_vat_minor  bigint       NOT NULL,
    initial_gross_minor bigint      NOT NULL,
    current_due_minor  bigint       NOT NULL,
    note               varchar(300),
    placed_at          timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    -- When (by the Payment Service's own clock) the last applied payment event happened. A payment event older than
    -- this is a late delivery and must not overwrite a newer payment status, even if its change looks legal alone.
    payment_event_at   timestamptz,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_restaurant_id_id UNIQUE (restaurant_id, id),
    -- order numbers are unique per restaurant; they are display identifiers, never an authorization token
    CONSTRAINT uq_orders_restaurant_order_no UNIQUE (restaurant_id, order_no),
    -- one order per cart: a cart can be checked out at most once, whatever the idempotency key
    CONSTRAINT uq_orders_restaurant_cart UNIQUE (restaurant_id, cart_id),
    CONSTRAINT ck_orders_fulfillment_status CHECK (fulfillment_status IN
        ('RECEIVED', 'PREPARING', 'READY', 'SERVED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN
        ('NOT_REQUESTED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_amounts CHECK (initial_net_minor >= 0 AND initial_vat_minor >= 0
        AND initial_gross_minor >= 0 AND initial_net_minor + initial_vat_minor = initial_gross_minor),
    CONSTRAINT ck_orders_due CHECK (current_due_minor >= 0 AND current_due_minor <= initial_gross_minor)
);

CREATE INDEX ix_orders_table_session ON orders (restaurant_id, table_session_id, placed_at DESC);
CREATE INDEX ix_orders_guest_session ON orders (restaurant_id, guest_session_id, placed_at DESC);
CREATE INDEX ix_orders_status ON orders (restaurant_id, fulfillment_status, placed_at);
-- listing the orders of a physical table (all of its table sessions)
CREATE INDEX ix_orders_table ON orders (restaurant_id, table_id, placed_at DESC);

CREATE TABLE order_items (
    id                    uuid          NOT NULL,
    restaurant_id         uuid          NOT NULL,
    order_id              uuid          NOT NULL,
    line_no               integer       NOT NULL,
    product_id            uuid          NOT NULL,
    product_name_snapshot varchar(200)  NOT NULL,
    ordered_quantity      integer       NOT NULL,
    cancelled_quantity    integer       NOT NULL DEFAULT 0,
    currency              varchar(3)    NOT NULL,
    unit_gross_minor      bigint        NOT NULL,
    tax_rate_bps          integer       NOT NULL,
    line_gross_minor      bigint        NOT NULL,
    line_net_minor        bigint        NOT NULL,
    line_vat_minor        bigint        NOT NULL,
    note                  varchar(300),
    created_at            timestamptz   NOT NULL,
    -- optional price version, supplied by the Menu Service once it provides one
    price_version         varchar(64),
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT uq_order_items_restaurant_id_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_order_items_line UNIQUE (order_id, line_no),
    CONSTRAINT ck_order_items_line_no CHECK (line_no > 0),
    CONSTRAINT fk_order_items_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_order_items_quantity CHECK (ordered_quantity > 0
        AND cancelled_quantity >= 0 AND cancelled_quantity <= ordered_quantity),
    CONSTRAINT ck_order_items_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_items_tax CHECK (tax_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_order_items_amounts CHECK (unit_gross_minor >= 0 AND line_gross_minor >= 0
        AND line_net_minor >= 0 AND line_vat_minor >= 0
        AND line_net_minor + line_vat_minor = line_gross_minor
        AND line_gross_minor = unit_gross_minor * ordered_quantity)
);

CREATE INDEX ix_order_items_order ON order_items (restaurant_id, order_id);

CREATE TABLE order_item_options (
    id                       uuid          NOT NULL,
    restaurant_id            uuid          NOT NULL,
    order_item_id            uuid          NOT NULL,
    option_id                uuid          NOT NULL,
    action                   varchar(10)   NOT NULL,
    quantity                 integer       NOT NULL,
    name_snapshot            varchar(150)  NOT NULL,
    unit_price_delta_minor   bigint        NOT NULL,
    CONSTRAINT pk_order_item_options PRIMARY KEY (id),
    CONSTRAINT uq_order_item_options_option UNIQUE (order_item_id, option_id),
    CONSTRAINT fk_order_item_options_item FOREIGN KEY (restaurant_id, order_item_id)
        REFERENCES order_items (restaurant_id, id),
    CONSTRAINT ck_order_item_options_action CHECK (action IN ('ADD', 'REMOVE')),
    CONSTRAINT ck_order_item_options_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_options_delta CHECK (unit_price_delta_minor >= 0)
);

-- Per-restaurant order number counter. The row lock taken by the upsert serializes number allocation per
-- restaurant until the checkout transaction ends, and a rolled-back checkout also rolls the counter back.
CREATE TABLE order_number_counters (
    restaurant_id uuid    NOT NULL,
    last_value    bigint  NOT NULL,
    CONSTRAINT pk_order_number_counters PRIMARY KEY (restaurant_id),
    CONSTRAINT ck_order_number_counters_value CHECK (last_value > 0)
);

-- ---- the order snapshot is immutable: only statuses/due (orders) and cancelled_quantity (order_items) may change -----
CREATE FUNCTION orders_keep_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id OR NEW.order_no IS DISTINCT FROM OLD.order_no
        OR NEW.cart_id IS DISTINCT FROM OLD.cart_id OR NEW.table_id IS DISTINCT FROM OLD.table_id
        OR NEW.table_session_id IS DISTINCT FROM OLD.table_session_id
        OR NEW.guest_session_id IS DISTINCT FROM OLD.guest_session_id OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.initial_net_minor IS DISTINCT FROM OLD.initial_net_minor
        OR NEW.initial_vat_minor IS DISTINCT FROM OLD.initial_vat_minor
        OR NEW.initial_gross_minor IS DISTINCT FROM OLD.initial_gross_minor
        OR NEW.note IS DISTINCT FROM OLD.note OR NEW.placed_at IS DISTINCT FROM OLD.placed_at THEN
        RAISE EXCEPTION 'the snapshot of order % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_snapshot
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_keep_snapshot();

CREATE FUNCTION order_items_keep_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.line_no IS DISTINCT FROM OLD.line_no OR NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.product_name_snapshot IS DISTINCT FROM OLD.product_name_snapshot
        OR NEW.ordered_quantity IS DISTINCT FROM OLD.ordered_quantity OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.unit_gross_minor IS DISTINCT FROM OLD.unit_gross_minor
        OR NEW.tax_rate_bps IS DISTINCT FROM OLD.tax_rate_bps
        OR NEW.line_gross_minor IS DISTINCT FROM OLD.line_gross_minor
        OR NEW.line_net_minor IS DISTINCT FROM OLD.line_net_minor
        OR NEW.line_vat_minor IS DISTINCT FROM OLD.line_vat_minor OR NEW.note IS DISTINCT FROM OLD.note
        OR NEW.price_version IS DISTINCT FROM OLD.price_version THEN
        RAISE EXCEPTION 'the snapshot of order item % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_items_snapshot
    BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION order_items_keep_snapshot();

CREATE FUNCTION order_item_options_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order item options are an immutable snapshot' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_item_options_immutable
    BEFORE UPDATE ON order_item_options
    FOR EACH ROW EXECUTE FUNCTION order_item_options_immutable();
