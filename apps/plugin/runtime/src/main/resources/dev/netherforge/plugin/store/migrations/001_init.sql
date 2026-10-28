-- The runtime's own state. Every row a project's package persists says whose it is: a
-- namespace column, or a name written in full (`shop:turret`).

-- Spawned centities: which exist, what they are, where they're anchored and which way they
-- face. The anchor and facing are written when the world saves, the chunk unloads or the
-- server stops (as the entities themselves are), not every time they change.
CREATE TABLE instances (
  id TEXT NOT NULL PRIMARY KEY,
  centity TEXT NOT NULL,
  world TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL DEFAULT 0
);

-- Each instance's entities: a display or hitbox per node, its anchor (the invisible entity
-- its displays, and the hitboxes sitting at the anchor, ride, so moving it is one teleport),
-- and orphans still to remove.
CREATE TABLE instance_entities (
  instance TEXT NOT NULL,
  entity TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('display', 'hitbox', 'orphan', 'anchor')),
  node TEXT,
  PRIMARY KEY (instance, entity)
);

-- Saved tables, each the prelude's JSON encoding: centity:data(), player:data(), and each
-- package's nf.data(name).
CREATE TABLE centity_data (
  instance TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE player_data (
  player TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE named_data (
  namespace TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (namespace, name)
);

-- The permission nodes a project's scripts set on players.
CREATE TABLE permissions (
  namespace TEXT NOT NULL,
  player TEXT NOT NULL,
  node TEXT NOT NULL,
  value INTEGER NOT NULL CHECK (value IN (0, 1)),
  PRIMARY KEY (namespace, player, node)
);

-- The worlds a project's scripts or its netherforge.json created, which it may unload and
-- delete. `terrain` is the project terrain (terrain/<id>.json) that generated it, so it's
-- loaded with it again; `dimension_type` the project dimension type (dimension_types/<id>.json)
-- it was made with, as the server knows it (basic:deep), so it isn't loaded while the server
-- lacks it. Each is NULL for any other.
CREATE TABLE worlds (
  world TEXT NOT NULL PRIMARY KEY,
  namespace TEXT NOT NULL,
  environment TEXT NOT NULL,
  terrain TEXT,
  dimension_type TEXT
);

-- When each package's `nf.schedule`s that were given an id last ran, as Unix time in
-- milliseconds, so a restart knows what it missed.
CREATE TABLE schedule_runs (
  namespace TEXT NOT NULL,
  id TEXT NOT NULL,
  last_run INTEGER NOT NULL,
  PRIMARY KEY (namespace, id)
);

-- What a player was before the cutscene they're watching started (one row each, gone when
-- they're put back), so a crash mid-cutscene can still put them back when they next join.
CREATE TABLE cutscene_states (
  player TEXT PRIMARY KEY,
  world TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL,
  pitch REAL NOT NULL,
  game_mode TEXT NOT NULL,
  can_fly INTEGER NOT NULL,
  flying INTEGER NOT NULL,
  spectating TEXT
);

-- The numbers a screen's maps keep for good: a project screen's, and those of a script's
-- screen made with a key, so a map item or a frame of one still shows it after a restart.
-- One row a map; a number is never given twice.
CREATE TABLE screen_maps (
  namespace TEXT NOT NULL,
  screen TEXT NOT NULL,
  tile INTEGER NOT NULL,
  map INTEGER NOT NULL UNIQUE,
  PRIMARY KEY (namespace, screen, tile)
);

-- The next map number screens may take: past every one ever given, kept ones or not, so a
-- number is never given twice, even after a keyed screen gives its numbers up
-- (screen:remove()) or a restart. One row.
CREATE TABLE screen_map_next (
  id INTEGER PRIMARY KEY CHECK (id = 0),
  next INTEGER NOT NULL
);
