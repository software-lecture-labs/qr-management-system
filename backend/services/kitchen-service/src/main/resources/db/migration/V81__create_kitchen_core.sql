-- Kitchen Service core read-model (KITCHEN-01). Version range V81-V90: docs/decisions/migrations.md
-- order_id / table_session_id / order_item_id / product_id are logical REFs to other services (no cross-DB FK).

CREATE TABLE kitchen_orders (
    id                    uuid         NOT NULL,
    restaurant_id         uuid         NOT NULL,
    order_id              uuid         NOT NULL,
    table_session_id      uuid         NOT NULL,
    table_number_snapshot varchar(40)  NOT NULL,
    order_number_snapshot varchar(40)  NOT NULL,
    status                varchar(20)  NOT NULL,
    received_at           timestamptz  NOT NULL,
    started_at            timestamptz,
    ready_at              timestamptz,
    cancelled_at          timestamptz,
    cancellation_reason   text,
    source_order_version  bigint       NOT NULL,
    source_event_id       uuid         NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_kitchen_orders PRIMARY KEY (id),
    CONSTRAINT uq_kitchen_orders_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_kitchen_orders_tenant_order UNIQUE (restaurant_id, order_id),
    CONSTRAINT uq_kitchen_orders_source_event UNIQUE (source_event_id),
    CONSTRAINT ck_kitchen_orders_status CHECK (status IN ('WAITING', 'PREPARING', 'READY', 'CANCELLED')),
    CONSTRAINT ck_kitchen_orders_source_version CHECK (source_order_version >= 0),
    CONSTRAINT ck_kitchen_orders_snapshots CHECK (
        length(btrim(table_number_snapshot)) > 0 AND length(btrim(order_number_snapshot)) > 0
    ),
    CONSTRAINT ck_kitchen_orders_timestamps CHECK (
        (status <> 'PREPARING' OR started_at IS NOT NULL)
        AND (status <> 'READY' OR (started_at IS NOT NULL AND ready_at IS NOT NULL))
        AND (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
        AND (started_at IS NULL OR started_at >= received_at)
        AND (ready_at IS NULL OR (started_at IS NOT NULL AND ready_at >= started_at))
    )
);

CREATE INDEX ix_kitchen_orders_queue ON kitchen_orders (restaurant_id, status, received_at, id);
CREATE INDEX ix_kitchen_orders_received ON kitchen_orders (restaurant_id, received_at, id);

CREATE TABLE kitchen_order_items (
    id                    uuid          NOT NULL,
    restaurant_id         uuid          NOT NULL,
    kitchen_order_id      uuid          NOT NULL,
    line_number           integer       NOT NULL,
    order_item_id         uuid          NOT NULL,
    product_id            uuid          NOT NULL,
    product_name_snapshot varchar(200)  NOT NULL,
    quantity              integer       NOT NULL,
    cancelled_quantity    integer       NOT NULL DEFAULT 0,
    options_snapshot      jsonb         NOT NULL,
    note_snapshot         varchar(1000),
    CONSTRAINT pk_kitchen_order_items PRIMARY KEY (id),
    CONSTRAINT fk_kitchen_order_items_order FOREIGN KEY (restaurant_id, kitchen_order_id)
        REFERENCES kitchen_orders (restaurant_id, id),
    CONSTRAINT uq_kitchen_order_items_tenant_item UNIQUE (restaurant_id, order_item_id),
    CONSTRAINT uq_kitchen_order_items_line UNIQUE (kitchen_order_id, line_number),
    CONSTRAINT ck_kitchen_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_kitchen_order_items_cancelled CHECK (cancelled_quantity >= 0 AND cancelled_quantity <= quantity),
    CONSTRAINT ck_kitchen_order_items_name CHECK (length(btrim(product_name_snapshot)) > 0),
    CONSTRAINT ck_kitchen_order_items_options CHECK (jsonb_typeof(options_snapshot) = 'object')
);

CREATE INDEX ix_kitchen_order_items_order ON kitchen_order_items (restaurant_id, kitchen_order_id, line_number);

CREATE TABLE kitchen_status_history (
    id                 uuid        NOT NULL,
    restaurant_id      uuid        NOT NULL,
    kitchen_order_id   uuid        NOT NULL,
    from_status        varchar(20),
    to_status          varchar(20) NOT NULL,
    actor_user_id      uuid,
    source_event_id    uuid,
    occurred_at        timestamptz NOT NULL,
    reason             text,
    CONSTRAINT pk_kitchen_status_history PRIMARY KEY (id),
    CONSTRAINT fk_kitchen_status_history_order FOREIGN KEY (restaurant_id, kitchen_order_id)
        REFERENCES kitchen_orders (restaurant_id, id),
    CONSTRAINT ck_kitchen_status_history_from CHECK (
        from_status IS NULL OR from_status IN ('WAITING', 'PREPARING', 'READY', 'CANCELLED')
    ),
    CONSTRAINT ck_kitchen_status_history_to CHECK (to_status IN ('WAITING', 'PREPARING', 'READY', 'CANCELLED'))
);

CREATE INDEX ix_kitchen_status_history_order ON kitchen_status_history (restaurant_id, kitchen_order_id, occurred_at);
