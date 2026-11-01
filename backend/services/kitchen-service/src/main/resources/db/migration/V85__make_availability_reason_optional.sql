-- KITCHEN-03: the reason of an availability change is optional (decision of the product owner).
-- A command may carry no reason code and no reason text; nothing is invented in their place.
-- V84 is left untouched; only the reason_code rules are relaxed (Kitchen range V81-V90, docs/decisions/migrations.md).

ALTER TABLE product_availability_commands ALTER COLUMN reason_code DROP NOT NULL;

ALTER TABLE product_availability_commands DROP CONSTRAINT ck_product_availability_commands_reason;

-- A reason code that is given must not be blank; NULL means "no reason given".
ALTER TABLE product_availability_commands
    ADD CONSTRAINT ck_product_availability_commands_reason CHECK (reason_code IS NULL OR length(btrim(reason_code)) > 0);
