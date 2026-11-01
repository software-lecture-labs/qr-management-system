-- KITCHEN-02: command identity for preparation commands (Kitchen range V81-V90, docs/decisions/migrations.md).
-- A client-supplied command id (Idempotency-Key) is stored with the status change it caused, so a repeated command can
-- be recognised as the SAME command (200, no change) instead of being guessed from the order's current status.
-- Nullable: existing rows and system-written rows (initial WAITING entry from an Order event) have no command id.

ALTER TABLE kitchen_status_history ADD COLUMN command_id uuid;

-- A command id may be used once per restaurant; reusing it for another order or command is a conflict.
CREATE UNIQUE INDEX uq_kitchen_status_history_command
    ON kitchen_status_history (restaurant_id, command_id)
    WHERE command_id IS NOT NULL;

-- Each status is entered at most once per order (WAITING at receipt, PREPARING, READY, CANCELLED). This is the
-- database-level guard against a duplicate transition, independent of application logic.
CREATE UNIQUE INDEX uq_kitchen_status_history_order_status
    ON kitchen_status_history (restaurant_id, kitchen_order_id, to_status);
