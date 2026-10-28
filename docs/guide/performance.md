# Limits and performance

Every script shares the server with Minecraft and with every other script, so
NetherForge keeps any one of them from taking the server down: scripts run in
a sandbox, under limits on how much each call may do. This page covers those
limits, and how to see and fix a script that's slowing the server down.

## The sandbox

Scripts get the safe parts of Lua: `string`, `table`, `math`, `utf8`,
`coroutine`, and the basics (`pairs`, `ipairs`, `pcall`, `error`, `tostring`,
`tonumber`, `type`, `select`, `setmetatable`, …). The shared library tables
are read-only, so one script can't change `string.format` for another.

Removed: `io`, `os`, `package`, `load`, `loadstring`, `dofile`, `loadfile`,
`debug`, `collectgarbage` and `warn`. Instead:

| Instead of                  | Use                                                                                 |
| --------------------------- | ----------------------------------------------------------------------------------- |
| `io`, files                 | [`nf.files`](../reference/nf.files.md) and [saved tables](scripting.md#saving-data) |
| `os.time`, `os.date`        | `nf.server.unix_time()` and [`nf.time`](../reference/nf.time.md)                    |
| `os.clock`, timing          | ticks: `nf.server.tick()`                                                           |
| `package`, `dofile`, `load` | [`require`](scripting.md#require), which reaches the project's own Lua              |
| sockets, HTTP               | [`nf.http`](../reference/nf.http.md), for websites the project declares             |

Everything runs on the server's main thread, one call at a time, so there are
no threads to coordinate. A script that needs to do something later schedules
it, or starts a task that waits (see [Waiting](modules.md#waiting)).

## The instruction budget

Every call into a script (its body, an event handler, a timer, a command, a
task carrying on after a wait) may run a limited number of Lua instructions:
**200,000** by default. That's plenty for normal work, and stops a runaway
loop from freezing the server.

A centity, menu or dialog can set its own budget with `script.budget` in its
JSON (at least 500), or with the **Budget** under its Script field in the
editor. Modules use the default.

Running past it raises an error at whatever line was running, which counts
like any other error: in a handler, that call fails; in the body, the script
is switched off. `pcall` can't catch it. The budget is per call, so a script
that does a little every tick never runs out. When one call starts another
(emitting a custom event, spawning a centity), the inner call has its own
budget, and what it ran counts against the outer one's too. Functions of the
API count as one instruction, however much work they do.

For long jobs, do a slice per tick. `nf.instructions_left()` says roughly how
many instructions this call has left:

```lua
local queue = {}

nf.every(1, function()
  while #queue > 0 and nf.instructions_left() > 10000 do
    local job = table.remove(queue, 1)
    job()
  end
end)
```

## Other limits

- **Time.** A call may run for at most a second, counting what it calls.
  Past it, it stops with "ran past its time limit", like running out of
  budget.
- **Big strings and slow library calls.** A string over 16 MB is refused, and
  so is a library call that would take more than about a tenth of a second: a
  pattern that backtracks badly on a long string, sorting hundreds of
  thousands of values. The error says which, at your line. For long text,
  split it into lines first.
- **Memory.** All scripts share 512 MB. If they go over, the script holding
  the most is stopped and what it held is freed. `/nf scripts` shows what a
  script holds once it's over a megabyte.
- **`__gc`.** A metatable with a `__gc` finalizer is refused.

## Seeing what scripts cost

A server tick has 50 ms for everything, Minecraft included. NetherForge times
every call into every script, so you can see which ones use the most.

`/nf scripts` lists them, the most expensive first:

```
3 scripts, 4.21 ms a tick (average / max ms a tick over the last 100 ticks)
3.90 / 6.12 centity tower 1a2b3c4d 2100 calls, 21 subscriptions, 0 tasks
0.29 / 0.85 module greeter 112 calls, 3 subscriptions, 1 task, 2 effects
0.02 / 0.40 dialog welcome 4 calls, 2 subscriptions, 0 tasks
```

The first number is the script's average milliseconds a tick over the last
100 ticks, the second its worst tick, then how many calls it had and what it
holds (subscriptions, tasks, playing effects and cutscenes). It shows the top
15; `/nf scripts all` shows every one. Time a script spends in something it
started (a centity it spawned, whose script then runs) counts for that other
script.

A script that takes more than **10 ms a tick on average** gets a warning in the
console and in the editor (the Console, and the Problems panel until the
script reloads), naming the file and line of its slowest handler. Server
owners can change the limit ([Server configuration](server-config.md#performance-warnings)).

On the dev server, the editor's **Profiler** panel (beside the Console) shows
where the server's ticks go: a timeline of the last six seconds, and a table
of every function your scripts ran with its total time, calls, average and
worst call. Click a function to open it at its line. On a real server,
`/nf profile 30` records the same for 30 seconds and writes a report (see
[Deploying](deploying.md#performance)).

## Keeping scripts fast

- **Tick handlers on many centities.** A handler on each of 200 instances is
  200 calls a tick. Use `every` (`this:on("tick", handler, { every = 10 })`)
  for what doesn't need 20 times a second: different centities then fall on
  different ticks, so 200 instances with `every = 4` cost about 50 calls a
  tick. Nobody listening to `tick` at all costs nothing.
- **Animate with clips.** Play animations rather than setting a node's pose
  every tick where you can: a playing animation costs almost nothing.
- **Building tables and vectors per tick.** Each event is a new table, and so
  is every `Vec3`; keep what you can between calls.
- **`player_move`** fires every time a player moves into another block. Only
  listen while you need it.
- **Reading many blocks.** `world:block(position):kind()` is a call per block.
  For a box of them, read them all with one `world:blocks(from, to, options)`.
