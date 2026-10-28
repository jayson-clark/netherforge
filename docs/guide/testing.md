# Testing your scripts

A project's scripts can be tested without Minecraft. `netherforge test` loads
the project on a **fake server**, runs every `*_test.lua` file in it and tells
you which tests passed, which failed and on which line. The editor does the
same from **Run → Run Tests**, in its **Tests** panel.

On the fake server your modules, centities, menus and dialogs start as they
do on a real server, their timers and events work, and players and the world
exist, but there's no game: no real terrain, no game client, no network. It
starts in a moment, so tests are fast enough to run on every save.

## Writing a test

A test is a function registered with `nf.test.case`, in a file whose name ends
in `_test.lua`. Put them wherever you like; a `tests/` folder is the usual
place.

```lua
-- tests/greeter_test.lua
local messages = require("greeter.messages")

nf.test.case("the welcome names the player", function()
  assert(messages.welcome("Hello", "Alex") == "<green>Hello, Alex!")
end)

nf.test.case("a first join marks the player welcomed", function()
  local alex = nf.test.player("Alex")
  assert(alex:data().greeter.welcomed == true)
end)
```

A test **passes** when its function returns, and **fails** when something in
it raises an error: an `assert` that doesn't hold, an `error(...)`, a mistake
calling the API. The report names the line. Use plain `assert` with a message
that says what you expected:

```lua
assert(count == 3, "count was " .. count)
```

A test file runs like a module: it can `require` your project's modules (and
files beside it, like `tests/helpers.lua`), and its own code runs once for
every test, before it, which is where to `require` and set up what the tests
share.

### Each test starts from nothing

Every test runs on a **fresh fake server** with the project loaded. A centity
one test spawned, a player it joined, a timer it started, what it saved with
`nf.data`, a row it wrote to the database: none of it exists in the next
test. Tests can run in any order, and one failing never explains another.

## What `nf.test` gives you

| Function                        | What it does                                                                                              |
| ------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `nf.test.case(name, callback)`  | Declares a test.                                                                                          |
| `nf.test.advance(ticks)`        | Runs the server forward whole ticks: timers fall due, `tick` handlers run, centities tick and mobs think. |
| `nf.test.player(name)`          | Joins a fake player (raising `player_join` as a real join does) and returns them.                         |
| `nf.test.raise(event, payload)` | Raises a server event to the scripts, as the server would, and returns `{ cancelled, event }`.            |

`nf.test` exists only while tests run: on a real server it's `nil`.

### Time

`nf.test.advance(n)` is how time passes. Nothing else moves the clock, so a
test is deterministic:

```lua
nf.test.case("the reminder fires every second", function()
  local count = 0
  nf.every(20, function() count = count + 1 end)
  nf.test.advance(60)
  assert(count == 3)
end)
```

Work that finishes later (a database query, `nf.worlds.copy`) is done by the
next tick, so a test knows when it lands. A task that waits for the database
needs a tick or two:

```lua
local joins
nf.task(function()
  local rows = assert(nf.db():query("SELECT joins FROM visits WHERE player = ?", { "Alex" }))
  joins = rows[1].joins
end)
nf.test.advance(2)
assert(joins == 1)
```

### Events

`nf.test.raise` takes any event `nf.on` takes (the reference lists them), and a
table of what the event carries, the same fields its handlers read:

```lua
local alex = nf.test.player("Alex")
local chat = nf.test.raise("player_chat", {
  player = alex,
  message = "buy diamonds",
  format = "<message>",
})
assert(chat.cancelled, "the filter should have stopped it")
```

The handlers run in the order the server runs them (for an event about a
player, an entity or a world, the handlers on that handle first, like
`alex:on("chat", ...)`, then `nf.on`), and can `stop()` or `cancel()` the event
or set its writable fields; `result.event` holds the payload as they left it.
A few events (a death, damage, a click on a centity) reach only `nf.on`
handlers when raised this way. A field the event doesn't have
is an error that lists the ones it does.

`raise` doesn't do what the server would after the event: a cancelled
`player_chat` is reported as cancelled, but nothing is sent or not sent. Read
the result, or what your handlers did. For what a fake player can _do_ (and so
which events a real action raises), join one and use the `Player` API.

### Players and the rest of the API

`nf.test.player` returns an ordinary `Player`, so a test uses the same API
your scripts do: `alex:data()`, `alex:has_permission(...)`, `alex:open_menu(...)`,
`nf.centities.spawn(...)`. What the fake server shows is what the API reports
about it; what only the real game computes (damage, pathfinding in real
terrain, redstone) isn't simulated, and the integration tests that run real
Paper cover it.

What a player's game is shown can differ from what the server holds, when an
item has a `view` handler. `nf.test.shown(player, slot)` is a stack as their
game was last sent it (`{ menu = true }` for a slot of the window they have
open, `"cursor"` for their cursor). The fake server sends what changed at the
end of each tick, as a real one does, so advance a tick first:

```lua
nf.test.case("the ruby shows its price in the shop", function()
  local alex = nf.test.player("Alex")
  alex:inventory():set_item(0, nf.items.create("ruby"))
  alex:open_menu("shop")
  nf.test.advance(1)
  local shown = assert(nf.test.shown(alex, 0))
  assert(shown.lore[#shown.lore] == "<gold>Sells for 5 coins")
  assert(#alex:inventory():item(0).lore == 1, "the real stack keeps its own lore")
end)
```

### Worldgen modules

A terrain script's modules can be tested without a world: `nf.test.terrain`
gives a stand-in for the script's `terrain`, with the noises and heights the
test chooses (a number everywhere, or a function of the place) and real
plans, each cell made once with numbers of its own.

```lua
local landforms = require("worldgen.landforms")

nf.test.case("high relief is mountains", function()
  landforms.init(nf.test.terrain({ noises = { ranges = 0.9, peaks = 1 } }))
  assert(landforms.kind(700, 200) == "mountains")
end)
```

What a chunk looks like is `netherforge preview`'s to draw.

## Running them

```sh
node .netherforge/bin/netherforge.mjs test
```

```
tests/greeter_test.lua
  PASS  the welcome names the player (1 ms)
  FAIL  a first join marks the player welcomed (309 ms)
        tests/greeter_test.lua:12: the greeter should have welcomed Alex

1 passed, 1 failed (2.1 s)
```

The exit code is 0 when every test passed, 1 when one failed, and 2 when the
tests couldn't run at all (the project has errors, no Java, no game data).

| Option                 | What it does                                                                 |
| ---------------------- | ---------------------------------------------------------------------------- |
| `[dir]` or a test file | The project (or any folder in it); a `*_test.lua` file runs only that file.  |
| `--filter <text>`      | Only the tests whose `file: name` contains the text, in any case.            |
| `--json`               | One JSON event per line instead of text. The editor reads this.              |
| `--junit <file>`       | Also write the results as JUnit XML, which CI systems show as a test report. |
| `--game-data <file>`   | Run on this game data export instead of the editor's cache for the version.  |

A test **errors**, rather than fails, when the test itself was fine but a
script of your project errored while it ran (a handler throwing, a module that
didn't start): the report names that script and line. A project the format
refuses (an error in a JSON file) can't be tested; run `check` to see why.

### What it needs

The command is the same JavaScript file as `check` and `format`; running tests
also needs **Java 21 or newer** and the **test runner**, a jar the editor
carries, `NetherForgeTest-<version>.jar`. The command finds Java the way the
editor does (`JAVA_HOME`, the Java the editor downloaded, `java` on your
`PATH`, the usual install folders; `NETHERFORGE_JAVA` names one explicitly) and
looks for the jar where the editor copies it when it starts
(`test-runner/` in its data folder), beside the script, or at
`NETHERFORGE_TEST_JAR`. Without one, it downloads the jar for its own version
from that version's release the first time, checks it against the release's
checksums, and keeps it in that same folder.

Tests run on the **real game data** of your project's Minecraft version, so
any block, item or entity type your project names is checked against the real
game. That data is what the editor's dev server exports the first time it
starts for that version, and it's kept per user, so **start the dev server from
the editor once** for the version in `netherforge.json`. Without it the tests
don't run (exit 2), and the message says so. `--game-data <file>` runs on an
export you name instead (the editor keeps its own at
`<data folder>/minecraft/<version>/server/game-data.json`).

### In CI

The command is on npm, and it downloads the test runner itself. A CI machine
has no editor, so give it the game data with `--game-data`: a copy of the
editor's export, committed or cached.

```yaml
- uses: actions/setup-node@v5
  with: { node-version: 22 }
- uses: actions/setup-java@v5
  with: { distribution: temurin, java-version: '25' }
- run: npx @netherforge/cli@1.0.0 test --junit test-results.xml --game-data ci/game-data.json
```

## In the editor

**Run → Run Tests** (or the **Tests** panel's button) runs every test and shows
each under its file, with its time. A failure shows where, why, what the
scripts logged meanwhile and the traceback; click a test to open its line. The
panel's badge counts the tests that didn't pass. Running tests needs the
project to be trusted, as the dev server does, and doesn't touch the dev
server or your files.
