/**
 * What every `data()` table (and `Item.data`) may hold, said once so each
 * function's doc says the same thing.
 */
export const DATA_VALUES =
  "It may hold strings, numbers, booleans, tables of them (keyed by strings, or a list numbered from 1), and the API's own values: `Vec3`s, `Location`s, `Item` tables, and `Player`, `Entity`, `Centity` and `World` handles, which come back as the same types (a handle to something gone answers `nil` or `false`, as handles do). Anything else (a function, a `Menu` window, a `Subscription`) is skipped when it's saved, with a line in the log naming where it was (`data.inventory[3]: a function can't be saved`)."

/** When a `data()` table is written out, and what survives what. */
export const DATA_SAVING =
  "The table is live: change it and the change is saved, at the next autosave (every five minutes), when the project reloads and when the server stops. It stays in memory across hot reloads rather than being read again, so a script that reloads finds it as it left it. A table that would take more than 1 MiB isn't saved (the last save stands), and the log says so."
