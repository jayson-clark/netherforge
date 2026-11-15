-- Whether a world was made by a room that isn't kept (a match's): it's deleted as its room closes, and at the next
-- start when the server went before the room closed (killed mid-match), so a kept room's worlds are never taken for
-- a match's leftovers.
ALTER TABLE worlds ADD COLUMN goes_with_room INTEGER NOT NULL DEFAULT 0;
