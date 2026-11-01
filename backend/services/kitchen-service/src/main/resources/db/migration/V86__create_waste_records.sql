-- KITCHEN-03.4: manual waste / spoilage records (Kitchen range V81-V90, docs/decisions/migrations.md).
--
-- A record only says what was thrown away, why, by whom and when. It never changes a stock quantity and never closes a
-- product: Kitchen keeps no stock, and the Menu service stays the only author of product availability.
-- product_id is a logical REF to the Menu service (no cross-database foreign key). order_id is optional and points to
-- Kitchen's own order read-model, so a composite foreign key keeps the order inside the same restaurant.
-- product_name_snapshot is only filled from names Kitchen itself received with an order; it is never made up.

CREATE TABLE waste_records (
    id                    uuid         NOT NULL,
    restaurant_id         uuid         NOT NULL,
    command_id            uuid         NOT NULL,           -- the client's Idempotency-Key
    product_id            uuid         NOT NULL,
    product_name_snapshot varchar(200),
    quantity              integer      NOT NULL,
    reason_code           varchar(40)  NOT NULL,
    description           varchar(500),
    order_id              uuid,
    recorded_at           timestamptz  NOT NULL,           -- server time, UTC
    recorded_by_user_id   uuid         NOT NULL,
    CONSTRAINT pk_waste_records PRIMARY KEY (id),
    CONSTRAINT uq_waste_records_command UNIQUE (restaurant_id, command_id),
    CONSTRAINT fk_waste_records_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES kitchen_orders (restaurant_id, order_id),
    CONSTRAINT ck_waste_records_quantity CHECK (quantity > 0 AND quantity <= 10000),
    CONSTRAINT ck_waste_records_reason CHECK (reason_code IN
        ('WRONG_PREPARATION', 'SPOILAGE', 'DROPPED_OR_DAMAGED', 'WASTE_AFTER_CANCELLATION', 'OTHER')),
    CONSTRAINT ck_waste_records_description CHECK (description IS NULL OR length(btrim(description)) > 0),
    CONSTRAINT ck_waste_records_name CHECK (product_name_snapshot IS NULL OR length(btrim(product_name_snapshot)) > 0)
);

CREATE INDEX ix_waste_records_time ON waste_records (restaurant_id, recorded_at DESC, id DESC);
CREATE INDEX ix_waste_records_product ON waste_records (restaurant_id, product_id, recorded_at DESC);
