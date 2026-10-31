-- QR closure contract (ADR-QR-001 K13): QR may confirm a close as CLOSED and still name RECONCILIATION_REQUIRED in
-- its blockers, because Order found a scope drift after QR had applied the closure locally. The closure stays a
-- definite fact; the flag only keeps that warning with the completed command so the panel can show it. QR keeps the
-- detailed source confirmation; Waiter stores no order or payment data and never reopens, resends or settles anything
-- because of this flag. Existing rows keep false: none of them was answered with the warning.
ALTER TABLE table_commands
    ADD COLUMN reconciliation_required boolean NOT NULL DEFAULT false;

-- Only a completed close can carry the warning.
ALTER TABLE table_commands
    ADD CONSTRAINT ck_table_commands_reconciliation CHECK (
        NOT reconciliation_required OR (command_type = 'CLOSE' AND status = 'COMPLETED'));
