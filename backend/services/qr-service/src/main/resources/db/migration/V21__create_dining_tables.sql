CREATE TABLE dining_tables (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_number VARCHAR(40) NOT NULL,
    display_name VARCHAR(120),
    capacity INTEGER,
    operational_status VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_dining_tables_restaurant_number UNIQUE (restaurant_id, table_number),
    CONSTRAINT uq_dining_tables_restaurant_id UNIQUE (restaurant_id, id),
    CONSTRAINT ck_dining_tables_number CHECK (table_number ~ '[^[:space:]]'
        AND table_number !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_dining_tables_name CHECK (display_name IS NULL OR
        (display_name ~ '[^[:space:]]' AND display_name !~ '^[[:space:]]|[[:space:]]$')),
    CONSTRAINT ck_dining_tables_capacity CHECK (capacity IS NULL OR capacity > 0),
    CONSTRAINT ck_dining_tables_status CHECK (operational_status IN ('AVAILABLE', 'OCCUPIED', 'CLEANING', 'DISABLED')),
    CONSTRAINT ck_dining_tables_active_status CHECK (
        (is_active AND operational_status <> 'DISABLED') OR
        (NOT is_active AND operational_status = 'DISABLED')),
    CONSTRAINT ck_dining_tables_version CHECK (version >= 0),
    CONSTRAINT ck_dining_tables_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_dining_tables_restaurant_state
    ON dining_tables (restaurant_id, is_active, operational_status, table_number, id);
