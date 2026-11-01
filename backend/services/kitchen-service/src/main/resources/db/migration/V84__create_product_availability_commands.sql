-- KITCHEN-03: audit and idempotency record of availability commands sent to the Menu service
-- (Kitchen range V81-V90, docs/decisions/migrations.md).
--
-- This is NOT the product's availability. The Menu service is the only author of that. A row says who asked for what,
-- under which Idempotency-Key, and what Menu answered (CONFIRMED / REJECTED) or that the answer is unknown (UNCONFIRMED).
-- product_id is a logical REF to the Menu service (no cross-database foreign key).

CREATE TABLE product_availability_commands (
    id                    uuid         NOT NULL,
    restaurant_id         uuid         NOT NULL,
    command_id            uuid         NOT NULL,           -- the client's Idempotency-Key
    product_id            uuid         NOT NULL,
    target_status         varchar(30)  NOT NULL,
    reason_code           varchar(40)  NOT NULL,
    reason_text           varchar(500),
    requested_by_user_id  uuid         NOT NULL,
    requested_at          timestamptz  NOT NULL,
    outcome               varchar(20)  NOT NULL,
    outcome_code          varchar(80),                     -- short safe code, never a message, address or token
    attempt_count         integer      NOT NULL DEFAULT 0,
    confirmed_at          timestamptz,                     -- Menu's own confirmation time (changedAt)
    last_attempt_at       timestamptz,
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_availability_commands PRIMARY KEY (id),
    -- A command id is used once per restaurant; the same id for another product/status/user is a conflict.
    CONSTRAINT uq_product_availability_commands_command UNIQUE (restaurant_id, command_id),
    CONSTRAINT ck_product_availability_commands_status CHECK (target_status IN ('AVAILABLE', 'OUT_OF_STOCK', 'TEMPORARILY_CLOSED')),
    CONSTRAINT ck_product_availability_commands_outcome CHECK (outcome IN ('UNCONFIRMED', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_product_availability_commands_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_product_availability_commands_confirmed CHECK ((outcome = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    -- Closing a product needs a reason (code is mandatory for every command; text is validated by the application).
    CONSTRAINT ck_product_availability_commands_reason CHECK (length(btrim(reason_code)) > 0)
);

CREATE INDEX ix_product_availability_commands_product ON product_availability_commands (restaurant_id, product_id, requested_at DESC);
CREATE INDEX ix_product_availability_commands_unconfirmed ON product_availability_commands (restaurant_id, requested_at) WHERE outcome = 'UNCONFIRMED';
