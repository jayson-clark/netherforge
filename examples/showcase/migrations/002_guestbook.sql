-- The guestbook the data booth keeps: one row per line a visitor signed. A second migration: each
-- runs once, in order, and the store remembers which have.
CREATE TABLE guestbook (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player TEXT NOT NULL,
  line TEXT NOT NULL,
  at INTEGER NOT NULL
);
