-- Order Service ORDER-01: persistent guest carts (Flyway range V41-V50, docs/decisions/migrations.md).
-- Money is stored as integer minor units (e.g. kurus). restaurant_id/table_*/guest_* are logical REFs to other services (no cross-DB FK).
--
-- ADR-QR-001 revision: the chain V41-V47 was consolidated with the Order Service owner's explicit permission, before
-- any merge to dev and before any shared database applied it. Later ALTER TABLEs and compatibility backfills were
-- folded into the CREATE TABLEs; the resulting schema is the same. Once shared, these files are not edited again.

CREATE TABLE carts (
    id                uuid         NOT NULL,
    restaurant_id     uuid         NOT NULL,
    table_id          uuid         NOT NULL,
    table_session_id  uuid         NOT NULL,
    guest_session_id  uuid         NOT NULL,
    status            varchar(20)  NOT NULL,
    currency          varchar(3),
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_carts PRIMARY KEY (id),
    CONSTRAINT uq_carts_restaurant_id_id UNIQUE (restaurant_id, id),
    CONSTRAINT ck_carts_status CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'EXPIRED')),
    CONSTRAINT ck_carts_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

-- A guest has at most one ACTIVE cart; concurrent first-adds resolve through ON CONFLICT on this index.
CREATE UNIQUE INDEX uq_carts_active_guest
    ON carts (restaurant_id, guest_session_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_carts_table_session
    ON carts (restaurant_id, table_session_id, status);

-- A cart that was ordered or expired can never be reactivated.
CREATE FUNCTION carts_keep_terminal_status() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'cart % is %, its status can no longer change', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_carts_terminal_status
    BEFORE UPDATE OF status ON carts
    FOR EACH ROW EXECUTE FUNCTION carts_keep_terminal_status();

CREATE TABLE cart_items (
    id                          uuid          NOT NULL,
    restaurant_id               uuid          NOT NULL,
    cart_id                     uuid          NOT NULL,
    product_id                  uuid          NOT NULL,
    quantity                    integer       NOT NULL,
    selection_key               varchar(64)   NOT NULL,
    note                        varchar(300),
    product_name_snapshot       varchar(200)  NOT NULL,
    currency                    varchar(3)    NOT NULL,
    unit_gross_minor_snapshot   bigint        NOT NULL,
    tax_rate_bps_snapshot       integer,
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL,
    -- optional price version, supplied by the Menu Service once it provides one
    price_version_snapshot      varchar(64),
    CONSTRAINT pk_cart_items PRIMARY KEY (id),
    CONSTRAINT uq_cart_items_restaurant_id_id UNIQUE (restaurant_id, id),
    -- Same product + same normalized selections (+ note) is one line; different selections are separate lines.
    CONSTRAINT uq_cart_items_selection UNIQUE (cart_id, product_id, selection_key),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (restaurant_id, cart_id)
        REFERENCES carts (restaurant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_cart_items_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cart_items_price CHECK (unit_gross_minor_snapshot >= 0),
    CONSTRAINT ck_cart_items_tax CHECK (tax_rate_bps_snapshot IS NULL OR tax_rate_bps_snapshot BETWEEN 0 AND 10000)
);

CREATE TABLE cart_item_options (
    id                              uuid          NOT NULL,
    restaurant_id                   uuid          NOT NULL,
    cart_item_id                    uuid          NOT NULL,
    option_id                       uuid          NOT NULL,
    action                          varchar(10)   NOT NULL,
    quantity                        integer       NOT NULL,
    name_snapshot                   varchar(150)  NOT NULL,
    unit_price_delta_minor_snapshot bigint        NOT NULL,
    CONSTRAINT pk_cart_item_options PRIMARY KEY (id),
    CONSTRAINT uq_cart_item_options_option UNIQUE (cart_item_id, option_id),
    CONSTRAINT fk_cart_item_options_item FOREIGN KEY (restaurant_id, cart_item_id)
        REFERENCES cart_items (restaurant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_cart_item_options_action CHECK (action IN ('ADD', 'REMOVE')),
    CONSTRAINT ck_cart_item_options_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_cart_item_options_delta CHECK (unit_price_delta_minor_snapshot >= 0)
);
