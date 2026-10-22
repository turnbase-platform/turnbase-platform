-- data-model.md §2, FR-DBQ-003 (IMP-M17-02): a user's live participation is found through the
-- seats not yet left, never through the user's departed history or other users' rows. Closing a
-- room does not set left_at, so a user keeps at most one stale seat in this index (a closed room
-- until their next join releases it).
DROP INDEX IF EXISTS room_participant_live_user_idx;
CREATE INDEX CONCURRENTLY room_participant_live_user_idx
  ON room_participant (user_id)
  WHERE left_at IS NULL;

-- data-model.md §5, FR-DBQ-001: the sweep's duty 1 reads this instance's own rooms and asks each for
-- its due reservations. The grace index spans the stale reservations of closed rooms (closing does not
-- clear them), so keyed on the deadline alone it grows with the retained history; keyed on the room
-- first, a lookup costs what that room holds. Built before the old one goes, so the sweep is never
-- without an index.
DROP INDEX IF EXISTS room_participant_grace_room_idx;
CREATE INDEX CONCURRENTLY room_participant_grace_room_idx
  ON room_participant (room_id, grace_expires_at)
  WHERE grace_expires_at IS NOT NULL AND left_at IS NULL;
DROP INDEX IF EXISTS room_participant_grace_due_idx;
