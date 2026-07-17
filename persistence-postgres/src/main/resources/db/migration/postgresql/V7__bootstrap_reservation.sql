-- Add workspace reservation to bootstrap state machine
ALTER TABLE bootstrap_state ADD COLUMN workspace_id TEXT;
UPDATE bootstrap_state SET workspace_id = NULL WHERE workspace_id IS NULL;
