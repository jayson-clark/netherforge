-- The project's own database, `nf.db()`: how many times each player has joined.
CREATE TABLE visits (
  player TEXT PRIMARY KEY,
  joins INTEGER NOT NULL
);
