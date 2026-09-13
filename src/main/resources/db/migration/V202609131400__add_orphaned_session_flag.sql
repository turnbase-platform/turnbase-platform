-- data-model.md §4.3 — FR-JOB-008(b)'s two-strike marker for reconciliation duty (b), whose
-- candidates are session IDs with no corresponding room row (the room may already be purged) and
-- so cannot use room.reconcile_flagged_at. No FK to session_teardown or room: their absence is
-- exactly the case this table exists to cover.
CREATE TABLE orphaned_session_flag (
  session_id         uuid PRIMARY KEY,
  first_observed_at  timestamptz NOT NULL DEFAULT now()
);
