-- KITCHEN-04: whole-order cancellation REQUESTS made by the kitchen (Kitchen range V81-V90, docs/decisions/migrations.md).
--
-- A row is a request, never a cancellation: the Order service alone decides whether an order is cancelled. While a
-- request is REQUESTED the kitchen order keeps its preparation status. APPROVED / REJECTED are written only from an
-- Order decision (a later task); the decision columns stay empty until then.
-- order_id is the Order service's id, kept in Kitchen's own read-model, so a composite foreign key keeps the request
-- inside the same restaurant. product_id / order_item_id are the optional stock reference of an OUT_OF_STOCK request
-- (checked against the order's own snapshot by the application).

CREATE TABLE cancellation_requests (
    id                          uuid         NOT NULL,   -- the request id, also the id of its outbox event aggregate
    restaurant_id               uuid         NOT NULL,
    order_id                    uuid         NOT NULL,
    command_id                  uuid         NOT NULL,   -- the client's Idempotency-Key
    status                      varchar(20)  NOT NULL,
    reason_code                 varchar(40)  NOT NULL,
    reason_text                 varchar(500),
    product_id                  uuid,
    order_item_id               uuid,
    requested_by_user_id        uuid         NOT NULL,
    requested_at                timestamptz  NOT NULL,
    decided_at                  timestamptz,             -- when the Order service decided
    decision_reason_code        varchar(60),             -- the Order service's own code for a rejection
    decision_order_version      bigint,
    decision_event_id           uuid,
    version                     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cancellation_requests PRIMARY KEY (id),
    CONSTRAINT uq_cancellation_requests_command UNIQUE (restaurant_id, command_id),
    CONSTRAINT fk_cancellation_requests_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES kitchen_orders (restaurant_id, order_id),
    CONSTRAINT ck_cancellation_requests_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_cancellation_requests_reason CHECK (reason_code IN ('OUT_OF_STOCK', 'CUSTOMER_REQUEST', 'KITCHEN_ERROR', 'OTHER')),
    CONSTRAINT ck_cancellation_requests_text CHECK (reason_text IS NULL OR length(btrim(reason_text)) > 0),
    -- An item reference needs its product; a REQUESTED request has no decision yet and a decided one always has a time.
    CONSTRAINT ck_cancellation_requests_item CHECK (order_item_id IS NULL OR product_id IS NOT NULL),
    CONSTRAINT ck_cancellation_requests_decided CHECK ((status = 'REQUESTED') = (decided_at IS NULL))
);

-- At most ONE open request per order: a second one (another key) is refused until the Order service has decided.
CREATE UNIQUE INDEX uq_cancellation_requests_open ON cancellation_requests (restaurant_id, order_id) WHERE status = 'REQUESTED';
CREATE INDEX ix_cancellation_requests_order ON cancellation_requests (restaurant_id, order_id, requested_at DESC);
