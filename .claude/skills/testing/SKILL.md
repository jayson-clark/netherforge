---
name: testing
description: NetherForge's test layers, what to run for which change, golden-file updates, writing runtime tests against the fake Platform, the integration and e2e harnesses, and the CI workflows. Read before writing tests, updating goldens, or changing CI.
---

# Testing

Few layers, each with one job. Most confidence comes from fast unit and golden
tests. The PR path also boots a real Paper server (the integration test), so
it isn't free of the network or of timing; a flaky test there is fixed or
quarantined the same day (see CI).

## Layers

1. **Unit tests, next to the code.** No network, no real clock (inject one),
   temp dirs only. Nothing sleeps for a time and asserts what happened
   meanwhile: move an injected clock (`TestServer(clock = …)`,
   `BridgeClient(clock = …)`), or wait for a condition with a generous
   deadline (10 s) and assert on the condition. `DebuggerTest`'s paused time
   and `BridgeClientTest`'s giving up are the models.
   - `format`: `kotlin.test` in `commonTest`, run on **both JVM and JS**
     (`:format:allTests`). A test passing on both is how we know the editor
     and plugin agree. A test sits in the package of what it tests
     (`commonTest/.../terrain/TerrainTest.kt`); the shared helpers
     (`TestFiles`, `withLua`, `ProblemGoldens`) and the tests of the whole
     (`GoldenTest`, `ProblemCoverageTest`) are at the root.
   - `apps/plugin/runtime`: JUnit against the **fake `Platform`**
     (`FakePlatform`, in `:plugin:testkit`'s main sources so a project's own
     script tests can use it too), running real Lua 5.4 scripts in a temp
     project through `TestServer` (see the plugin-runtime skill). A test
     lives in the package of what it tests (`screen/ScreenTest`,
     `wasm/WasmModulesTest`, `binding/ConformanceTest`); the shared support
     (`TestServer`, `LuaChecks`, `NfvFiles`) is at the root. A Lua
     check is written with `LuaChecks` (`runtime/src/test/.../LuaChecks.kt`),
     never a copy of its own: `check(label, got, want)`, `near(label, got,
want[, tolerance])` and `fails(label, fn, message)` log `FAIL <label>: got
…, want …` only for what's wrong, and the script logs `done` last, so
     `assertEquals(LuaChecks.DONE, result)` fails naming each bad expression
     and fails too when the script never ran. `LuaChecks.run(body, files,
setup, after)` runs it as the console's `/run`, `runModuleBody` as a
     module's body, `LuaChecks.command(body)` is the file for a server a test
     builds itself, and `server.runChecks()` / `server.output()` (errors as
     `ERROR …`, then logged lines) read the result.
   - **A project's own script tests** (`*_test.lua`, `nf.test`): `netherforge
test` and the editor's Tests panel run them with `:plugin:test-runner`
     (`apps/plugin/test-runner`, the jar `NetherForgeTest-<version>.jar`). It
     starts a fresh `FakePlatform` and runtime for **every test**, on the
     **real game data** of the project's Minecraft version: the dev server's
     export from the per-user cache, given as `--game-data <file>` (the CLI
     finds the cache itself or takes `--game-data`; the editor passes it; no
     data, or another schema, is exit 2 saying to start the dev server once;
     see the game-data skill). `FakePlatform(supported, game)` takes the game;
     its default, `GAME`, is for the runtime's own tests and the runner's
     (`ScriptTestRunner(project, game)` is always given one). (The file's
     body runs once per test, declaring cases with `nf.test.case`; the runner
     picks one), reports text, JSON lines (`--json`, what the editor reads) or
     JUnit XML (`--junit`), and exits 1 on a failure or error, 2 when the
     project can't run. `ScriptTestRunnerTest` runs small temp projects (what
     fails, what errors, isolation, `advance`, `raise`, `click`, `release` and `input`, `require`, the filter,
     the output formats); `ExampleTestsTest` runs `examples/showcase/tests/` (so
     the example is exercised, and `nf.test` is checked on the real project) and
     `examples/network/tests/` (the network demo, on the runner's fake network).
     The **project templates** (`examples/template_minigame`, `template_shop`,
     `template_rpg_mob`, what the editor's Add template puts into a project,
     see editor-packages) are run the same way (`ExampleTestsTest`, each on its own),
     are loaded clean and canonical by `GoldenTest` like every example (and
     `templatesExportWhatTheyHaveAndHaveTests` holds each to exporting every
     resource it has and having `*_test.lua` tests), are checked by LuaLS
     (`luals.check.test.ts`), and bundled into the editor (`core/templates.ts`,
     tested in `core/store/templates.test.ts`, `Welcome.test.tsx` and the
     `packages.spec.ts` e2e). They exercise `nf.test`, `nf.db`, natural
     spawning, menus, teams and timers, so an API change that breaks one fails
     CI. CI has no real game data (only a live server exports it), so
     `ExampleTestsTest` runs them on the small `FakePlatform.GAME` fixture: a
     block or item a template names has to be in it.
     The runtime half (`testing/ScriptTests`, `NfTestImpl`, the `testOnly`
     namespace, `RaisableEvents`) is in the plugin-runtime and lua-api skills.
   - `apps/editor/src`: Vitest + Testing Library. Assert on behaviour, not DOM
     snapshots; find things by role and name (`getByRole`), never test ids or
     `querySelector`: a control a test can't name is an a11y bug to fix in the
     component. A `*.node.test.ts` runs in Node (type-checked by
     `tsconfig.node.json`): `core/luals/luals.node.test.ts` drives the real
     lua-language-server the way the editor sets it up.
     - **The shared set-up** is `src/testing/workspace.ts`:
       `openExampleWorkspace()` (a workspace store with `examples/showcase` open,
       `{ backend, workspace, ws }`), `openExampleApp()` (every store as
       `createApp` wires them, `{ backend, app }`), `exampleWorkspace()` (not
       opened yet, to attach something first) and `exampleBackend()`; each
       takes `{ project, backend }` (the example's files, the memory backend's
       other options). Don't build that by hand in a test.
     - **No wall-clock waits.** A test never sleeps a fixed time or relies on
       one timer beating another. Code that runs on timers (the memory
       backend's file events, validation's `VALIDATE_DELAY_MS`, the layout's
       `SAVE_DELAY_MS`, MCP tools' settling, the fake server) is tested on
       `vi.useFakeTimers()` and moved on with `settle(ms)` (which adds
       `HOPS_MS` for chains of 0 ms timers, each 1 ms on the fake clock),
       `advanceUntil(promise)` for code that sleeps until something answers,
       or `vi.advanceTimersByTimeAsync` at a delay's exact end. What finishes
       off the JS thread (WebCrypto hashing a package, LuaLS, a worker) or in
       React is awaited as state: `vi.waitFor`, Testing Library's
       `waitFor`/`findBy…`, both bounded. Component tests run on real timers
       (Testing Library's waits don't drive vitest's fake clock).
   - `apps/editor/src-tauri`: `cargo test` with temp dirs, on every OS. A
     test that needs a process runs `examples/fake_server.rs` (Java with Paper
     or the test runner, lua-language-server, an orphaned server;
     `testing::fake_server()`), never `/bin/sh`. No sleeps: wait on a condition
     with `testing::until` (30 s `PATIENCE`), and know a watcher is live with
     `watcher::until_watching` (a probe file's event) before relying on it.
2. **Golden and contract tests.**
   - `examples/*` must load clean and be byte-for-byte canonical (JVM and JS),
     and hold a resource of every kind in format's registry (binary kinds
     excepted); `KindsTest` makes, classifies and loads every registered kind.
   - `packages/format/testdata/invalid/<case>/` is a small project plus
     `expected.json`, the exact problems it must produce; every code in it
     must be in `ProblemCodes`, with its severity. `GoldenTest` and
     `PackagesTest` check **every case before failing** and fail once with all
     the cases that differ (`ProblemGoldens`), on the JVM and in JS, so one
     run shows every golden a change moved.
   - **Every problem code is produced by a test** (`ProblemCoverageTest`): a
     code is in some golden `expected.json` (`invalid/` or `packages/`), or it's
     in that test's `testedElsewhere` with the test that produces it and why a
     golden can't (it needs game data, which goldens load without; JSON can't
     hold it; it's too big for a file). The test named must exist and name the
     code. A code only the plugin reports while a project runs is in
     `ProblemCodes.reportedAtRuntime`, and the runtime's own `ProblemCoverageTest`
     (`apps/plugin/runtime/src/test/.../ProblemCoverageTest.kt`) names the runtime
     test that produces each. `untested` lists the gaps: an entry there is a gap
     to close, never a place to park a new code. A new rule adds a golden case,
     not an entry.
   - `packages/format/testdata/references/<example>.json` is every reference
     each example makes, as the walker finds it.
   - Packages: `examples/showcase` depends on `examples/library` (`../library`),
     so anything that copies the showcase copies library beside it (the runtime's
     `TestServer.example`, the CLI's tests, the integration test's
     `copyExample`, the editor's seed). `TestServer` runs the showcase's files
     as the dev server runs them (`TestServer.isShowcase`: bots and web routes
     on, the main world generated by NetherForge), and its expo builds 32
     booths at tick 20, so a runtime test that counts what's in the world
     (centities, particle effects, menu windows) or stands at the origin
     filters to what it made or works elsewhere. `examples/showcase/netherforge.lock` is
     checked against the library's hash on JVM and JS (`GoldenTest`), and
     `packages/format/testdata/packages/<case>/` holds several projects side
     by side (`app` is loaded) with the problems they must produce
     (`PackagesTest`).
   - Git packages: `packages/format/testdata/packages/git*/` stand in for
     repositories with `repos.json` (`TestPackages`: url → refs and commits →
     folders), so format needs no git; the hosts that fetch test against
     local bare repositories by `file://` URL, made with the system's `git`
     (the CLI's `git.test.ts`, the Rust `fs::git` tests and the contract
     suite), never the network.
   - `docs/format/problems.md` is the problem catalogue written out
     (`ProblemCatalogueTest`, JVM).
   - `docs/tests/format-docs.test.ts` (the docs package, vitest on format's JS
     build): every whole-file `json` block in `docs/format/*.md` validates as
     its kind, and every key of each kind's JSON Schema is named on its page
     **as a key**: inline code that is the key, a path of keys
     (`terrain.base`), an object's shape or one quoted entry
     (`"invert": true`); a key of a `json` example on the page; a table row's
     first cell; or a word of a heading. A word in prose, or inside other
     code, doesn't count. A snippet is marked `json partial` (project-format
     has the convention).
   - The CLI's tests (`apps/cli/src/*.test.ts`) write only to temp folders and
     delete them (`afterEach`, or `afterAll` for a per-file copy).
     `preview.test.ts` copies `examples/showcase` and `library` once per file (a
     test that changes the project asks for its own copy) and runs
     `netherforge preview`: one plumbing check (the PNG equals
     `terrainPreviewer` + `mapPixels`/`slicePixels` from
     `packages/terrain-preview`) and independent ones, a flat terrain whose
     pixel colours are worked out by hand from `draw.ts`'s rules, never through
     the drawing code; a problem printed as `check` does, bad options exit 2,
     and a generator's script run. `java.test.ts` and `project.test.ts` run the
     Java search and project reading on a faked machine and temp folders (no
     Java needed). `packages/terrain-preview` tests the colours against the
     real JS build, `nbt.ts` (every tag type big-endian, gzip/zlib/raw, writing
     back, refusals) and `structure.ts` (both palette spellings, bare 26.x ids,
     several palettes, the captured server file) on their own, and its
     `script.test.ts` runs format's script fixture on Node's Lua
     (`loadNodeLua`). No test asserts wall-clock time: a bound flakes on a slow
     runner and proves nothing the pixels don't.
   - **3D terrain** (W5.11): format's `TerrainDensityTest` (overhangs and islands, every surface topped, the sea,
     caves' depth, decorations on islands, blending, the map and spawn, the script's `density` stage on both Luas,
     `testdata/terrain/density.txt`), `TerrainDensityTimingTest` (jvmTest, a benchmark printing the cost against heights: skipped unless
     `NETHERFORGE_BENCH=1`, like the runtime's; run with `-i`), `PaperWorldGeneratorsTest`'s 3D world (every block, the game's heightmap, the spawn) and the
     runtime's `TerrainTest` (chunk threads).
   - **Terrain scripts** (W5.6): format's `TerrainScriptTest` runs every
     case on the JVM (luajava) and in JS (wasmoon) through `withLua`, and
     `testdata/terrain/script.txt` is the one golden both must make (rewrite it
     with `UPDATE_GOLDEN=1`, then check JS agrees); `TerrainScriptApiTest` holds
     the glue to the spec's JSON. A test with Lua in the editor's vitest is a
     `*.node.test.ts` (`// @vitest-environment node`): wasmoon can't find its
     WebAssembly in jsdom.
   - `packages/format/testdata/bridge/session.ndjson` is the dev bridge's recorded
     session; every side must round-trip it. `BridgeTest` holds every line to
     the protocol: an unknown method only where the recording calls one on
     purpose (listed, answered method-not-found), params that don't decode
     only where they're answered invalid-params, a response only to an open
     request.
   - format's JS facade (`jsMain/.../*Exports.kt`) has `ExportsTest` (jsTest):
     each export's answer shape (a sealed result's `type`, nulls left out) and
     problems answered, not thrown. format's JS tests run under mocha with a
     30 s per-test timeout (`build.gradle.kts`); keep a test well under it on a
     CI runner by computing only what it checks (one `sampler()` for many
     columns, not a fresh one per column).
   - `packages/format/testdata/game-data/bundle.json` is a small game data
     export in today's shape: `GameDataTest` looks things up in it and checks
     its `schema` is `GameDataBundle.SCHEMA`; the editor's backend checks it's
     its own `GAME_DATA_SCHEMA`.
   - API conformance: the runtime exposes exactly what `packages/api/` declares.
   - lua-language-server: the generated stubs check clean over the examples
     and every spec example (`luals.check.test.ts`; without the binary it's
     skipped locally, saying why, and fails when `CI` is set), and the editor's setup
     (project names, its plugin) gives a diagnostic, a project name completed
     and a definition across a `require` (`luals.node.test.ts`). Both run the
     pinned LuaLS, which `pnpm test` fetches first (`tools/luals.mjs`, cached).
   - The plugin's own Lua (the prelude's modules, the caps, the generated
     bindings and schema) is type-checked by `pnpm lint` (`tools/lint-prelude.mjs`):
     the same pinned LuaLS, `--check` over `apps/plugin/runtime/src`, and any
     diagnostic, an unused local included, fails it. `PreludeModulesTest` holds
     the module list to the folder and every module to half of Lua's limit of
     200 locals.
   - **Platform contracts**: one abstract suite per `Ops` interface (below),
     run against the fake here and against Paper in the integration test, so
     the fake can't say anything the real server doesn't.
   - **The editor's backend contract**: `apps/editor/src/core/backend/contract.json`,
     one case list in the UI's terms (`Backend` calls, events listened to and
     expected, another program's file changes, an agent's MCP request, fetching
     the backend's URLs; the format is `contractRunner.ts`'s header), run on
     `MemoryBackend` (`contract.test.ts`) and on `TauriBackend` with
     `@tauri-apps/api`'s IPC mocked to forward to the Rust commands
     (`contract.tauri.test.ts`, which starts the host in
     `src-tauri/src/commands/contract.rs` with cargo). Both are vitest, so
     `pnpm --filter @netherforge/editor test` builds the Rust backend once.
     `contract.test.ts` also holds every `Backend` member to a case or to
     `EXCLUDED` with a reason (those get `tauri.test.ts` and
     `tauriMenu.test.ts`, plugin IPC mocked). Its `repositories` are real bare
     git repositories on the Rust side (`$GIT/<name>`) and `gitRepos` on the
     memory backend; a step's `save` keeps its answer for the steps after it
     (`$<name>`). Cases start with nothing trusted (`trusted: []`), as the app
     does: one that runs the server or fetches calls `trustProject` first. The
     memory backend every UI test uses can't promise what the app doesn't do;
     Rust is the truth when they disagree (see the tauri-backend skill).
3. **Integration**: `pnpm test:integration` runs every scenario against
   every Paper adapter (`apps/plugin/integration`, `:plugin:integration`), each
   on a headless Paper of the adapter's own Minecraft version with its plugin
   and bots jars, driven over the dev bridge like the editor drives it. It
   downloads each adapter's pinned Paper build, and Paperclip downloads and
   patches the Mojang server jar on first start (kept in the Gradle user home
   and `apps/plugin/integration/build/integration/<minecraft>/server/`
   locally, by `actions/cache` per version in CI).
   `integrationTest-<minecraft>` runs one version's (`--tests '*BotScenario*'`
   one scenario); `integrationTest` runs them all, **the versions in
   parallel**: the configuration cache (on in `tools/gradle/gradle.properties`)
   is what lets Gradle run one project's tasks at once. Nothing is shared
   between versions (each has its own server folder, reports, temp copies of
   the example and free ports), so a scenario must keep it that way: write
   only into its `TestProject` copy and `Adapter.server`, never into
   `examples/` or the fixtures, and take ports from `ServerSocket(0)`. A
   version is a 1 GB test JVM driving one 2 GB Paper server, so as many run
   at once as there are 6 GB of memory (all four from 24 GB; the
   `paperServers` build service's parallel usages);
   `-Pnetherforge.integration.parallel=<n>` sets it. All four together take
   about 10 min on an 18-core, 48 GB machine, against 42–72 min one after
   another.
   `DebuggerScenario` holds a real server at a breakpoint for 40 s with a bot
   online (past Paper's watchdog warning and the keep-alive limit); its DAP
   is raw JSON over `Editor.notify`. `ScheduleScenario` runs its server with
   the test-only `-Dnetherforge.test.wall-clock-rate=60` (through
   `PaperServer`'s `jvmProperties`; see plugin-persistence's "Schedules"), so a
   cron schedule for every minute fires on the real tick every second.
   - **Scopes** (`-Pnetherforge.integration.scope=full|pr|quarantine`,
     `full` by default): a scenario whose checks don't change with the
     Minecraft version is `@VersionIndependent` (`support/VersionIndependent.kt`, the JUnit
     tag `version-independent`): `ScheduleScenario`, `DebuggerScenario`,
     `SettingsScenario`, `SpawnRatesScenario`, `CutsceneScenario`,
     `TerrainScenario` (its version-sensitive parts are `MainWorldScenario`'s,
     `DimensionTypeScenario`'s and `StructureGenerationScenario`'s). The `pr`
     scope leaves them out on every version but the newest (the build works
     it out from the adapters); `full` runs everything. Untagged means it runs
     on every version: anything touching world storage, datapacks, registries,
     packets or goals (`ContractScenario`, `BotScenario`, the world and
     datapack scenarios, `BlockScenario`, `MobScenario`, `GameDataScenario`,
     `ReloadScenario`, …).
   - **A scenario is a class** (`support/Scenario.kt`) whose tests are its
     steps, in `@Order`, against one server running a copy of
     `examples/showcase` (retargeted to the adapter's version) with fixtures
     added: started before the first step, stopped cleanly after the last.
     The copy is the example as the repo holds it: what its `.gitignore`
     keeps out (the editor's `fonts/default.json` from the client imported
     on this computer, for that client's version; `.netherforge/`) is left
     behind, so a run starts from the same files on every machine. Two
     things differ from the dev server, so the copy loads clean: the showcase's
     main world entry loses its terrain (the expo's plaza needs `bukkit.yml` to
     route the main world; `TestProject.setWorld` is how a scenario that does
     names one, replacing the entry rather than adding a second), and
     `PaperServer` turns web routes on (`web.enabled`), which the showcase
     declares. Its expo builds its booths in every scenario's main world a
     second after it loads; a booth that fails only logs. A
     failing step skips the steps after it in its scenario (they'd fail only
     for what it left undone); other scenarios run regardless.
   - **Fixtures are files**: `src/test/fixtures/<name>/` is laid over the
     project file by file (`TestProject("world")`); `edits/` holds texts a
     step writes in later. Lua there is checked by stylua like any.
   - **Shared helpers** in `support/`: `Adapter` (what this run is against),
     `PaperServer` (the server folder, starting and stopping, what the
     plugin's store holds: `stored(sql)`, read beside the running server; `prepare` writes `server.properties`,
     `spigot.yml` and `bukkit.yml` afresh each time, with `levelSeed` and `mainWorldGenerator` for a scenario whose
     main world matters, so nothing a scenario routed outlives it), `Editor` (the bridge: `request`, `next`, `logged`, `line`, `settle`, `assertNone`, `run`,
     `reload`), `Bots` (acting and waiting on what a bot sees),
     `Maps` (the editor's world capture).
   - **What's there**: `ReloadScenario` (hot reload of every kind, typed
     commands, the resource pack, a restart), `GameDataScenario` (the export),
     `WorldScenario` (the world, entity, team and event API, physics),
     `MobScenario` (attributes, pathfinding, goals, centity paths),
     `BotScenario` (below), `BlockScenario` (blocks: the resource pack's blockstates, a script and a bot placing one, mining it with a pickaxe for its loot table's drops, a lamp drawn by a centity, a player's note block still tuned, a restart keeping the data), `ManagedWorldsScenario`, `CaptureScenario`, `StructureGenerationScenario` (a captured structure with a centity marker generates in a new world, once), `TerrainScenario` (the example's terrain in a real world, its areas a fixture biome without features so the ground compares exactly: heights (blended between areas' own terrain), layers, decorations and the custom ore (adopted as the project's block) are what format's own generator makes for the seed, the example's tree, which the scenario reads with the testkit's `StructureFiles` and the server with the game's loader, standing where format puts it, a project structure generates in it, a saved file shapes only later chunks, a restart keeps the generator, and scripts read its biomes, find one with `world:locate_biome` and hear `chunk_generated`), `BiomeScenario` (the example's `biomes/ruby_grove.json`: in the start-up datapack, decorated with exactly its features where the generator puts it, found by `/locate biome`, a change asking for a restart), `DimensionTypeScenario` (a fresh main world that `netherforge.json` names the example's `deep` dimension and generator for: the start-up datapack's replaced overworld type, the world's heights, format's generator filling it from -128, a script's world of the type with blocks at both ends of its limits (deleted after), taking the dimension out asking for a restart), `DatapackScenario` (the `datapacks` fixture's datapack replaces `minecraft:overworld`'s noise settings, one overlay per
     shape, with a project density function: a `"normal"` world made by `nf.worlds.create` has its basalt shelf; then a
     placed feature the server can't read makes it refuse to start, `PaperServer.refuses` waits for the process to stop, and
     the next start runs without the datapacks with a `runtime.datapack` problem on that file until it's fixed),
     `MainWorldScenario` (the server's main world made by the example's generator, routed by `bukkit.yml`: its blocks against format's generator for `level-seed`, the spawn, the adopted ore, a saved file without a restart, another generator in `netherforge.json` asking for a restart and taken by it), `CutsceneScenario` (a bot spectates a cutscene's camera along its path and is put back, in the game mode and place it had, when it finishes, is stopped, quits mid-way or the server stops),
     `SpawnRatesScenario` (spawn limits and intervals from `netherforge.json`
     and scripts, a reload re-applying them), `SettingsScenario` (`/nf settings set` reaching a `setting_changed`
     listener and restarting a reader, over the `settings` fixture), and
     `ContractScenario` (the Platform contract suites, below; `PaperWorldGeneratorsTest` is Paper-only: a world's real blocks against format's generator, a scripted generator's too, run on the server's chunk threads). A difference
     between versions an assertion must allow (where a world's files are)
     asks `Adapter`.
   - **Waiting for a frame, match what you mean**: `editor.next { ... }` takes
     the first frame its predicate accepts, and a loaded machine sends others
     meanwhile. Match a `ScriptError` by its source file or message, never as
     the first one. `editor.logged(...)` waits for an exact log line,
     `editor.line(first)` for the next line whose first field is `first` (its
     other fields back, what a fixture's probe reports).
   - **Never sleep, then assert.** Up to four servers share a machine, so a
     tick or a chunk can take seconds. Every wait has one limit,
     `WAIT_SECONDS` (60 s, `support/Waits.kt`), and returns as soon as it
     can: `eventually(what, poll = { ... }) { accept }` asks again until the
     answer is accepted (a count after chunks load, a file written off the
     main thread, where the camera is), `Bots.eventually`/`heard` poll a bot
     the same way. Something that must happen a number of ticks later is
     counted in ticks by the fixture (`nf.after(5, ...)`), not in sleeps. A
     check that something did **not** happen is `editor.assertNone(what) { }`,
     which first waits for everything the server reported until now
     (`Editor.settle`: a request the main thread answers, sent after
     whatever it said before).
   - **A timeout says what came instead**: it names what it waited for and
     lists the last 40 frames with their content (log fields, script errors
     with their file and line, problems, answers); `logged` and `line` also
     show the log lines with the same first field, field by field ("field 3:
     "minecraft:stone", not "minecraft:gold_block""). A bridge the plugin
     closed fails at once.
   - **No script fails unless a step says so.** After every step that passed
     (and across a `restart`), `Scenario` settles and fails the step on any
     `ScriptError` no wait took. A step that breaks a script on purpose calls
     `expectScriptErrors("why") { it.source?.file == "..." }` (by source or
     message) for the rest of the scenario, and waits for the error it checks
     with `next` (`MobScenario`, `ReloadScenario`). Servers run with
     `performance.warn-ms: 0`, so a slow-script warning is never one.
   - **Bots**: `BotScenario` joins bots (fake players, in the plugin; see
     the plugin-bots skill) on an online-mode server and checks
     what only a player can do or see: the join, chat and commands, clicking
     centities, menus, items, recipes, dialogs, titles, the sidebar, boss
     bars, teams and the player list, the resource pack, per-player hiding,
     what only one player is shown (a block, equipment, a book, the camera),
     grants, advancements, what a script hears of walking, teleporting, drops
     (one cancelled), swapping hands, placing, breaking, picking up, eating,
     clicking a block and a mob, and a death and respawn (its `actions`
     fixture), and a ban keeping a bot out (last). Anything player-facing
     gets a step there.
   - **A package's database** (`nf.db()`): `PackageDatabaseTest` writes real
     SQLite files under the test server's state folder. Async work finishes
     on the workers, so `server.tick(n)` (which waits for the workers each
     tick) between a task's awaits; a task that awaits N calls needs N ticks.
     `RemoteDatabaseTest` is the same for a named connection (`nf.db("network")`):
     H2 in MySQL or PostgreSQL mode is the server, handed to the runtime as
     `TestServer(connectionSources = ...)` with `databases = DatabasesConfig(...)`;
     one script is run against both and must answer alike. The pool itself
     (HikariCP) and the real drivers aren't exercised: that needs a MySQL or
     PostgreSQL server.
4. **Editor UI flows**: `pnpm test:e2e`, Playwright against the e2e web build
   (`vite build --mode e2e`), which runs on the memory backend with an
   in-memory project, in the spec files under
   `apps/editor/e2e/`. Only flows nothing lower can cover go here: Monaco,
   WebGL and the client's pictures, the real worker, layout and pointer
   gestures, focus across the workbench, one canonical save per editor, flows
   across editors. What a field does to the model is the editor's component
   test (`editors/<kind>/*Editor.test.tsx`), and a store's rules are the
   store's; an e2e flow says in a comment which test covers the rest. Keep each
   flow to one story (a shared set-up helper, not one long test), compare by
   role and accessible name (`ariaSnapshot`, not `innerHTML`), and bound every
   wait (`expect.poll` with a timeout, never a loop on `waitForTimeout`). There's no
   lua-language-server there (scripts are highlighted only), so Lua language
   features are tested against the real server in vitest, above.
   - **Screenshots** of the pixel-exact previews (a menu's window with its
     skin, a skin over its window, a dialog, text with a glyph, a tooltip):
     `e2e/screenshots/`, the `screenshots` project, compared pixel for pixel
     (`maxDiffPixels: 0`) with the pictures beside the spec. Fonts and
     anti-aliasing differ by OS, so the browser always runs in the official
     Playwright container (`mcr.microsoft.com/playwright:v<installed
version>-noble`, linux/amd64): `pnpm test:screenshots`
     (`tools/screenshots.mjs`) starts its `playwright run-server` and runs the
     project against it, the tests and the web build staying on the host
     (the container reaches them through `exposeNetwork`). Needs Docker; under
     `pnpm test:e2e` (no container) they're skipped. On an Arm Mac the image
     runs emulated, and QEMU crashes Chromium: use Rosetta, e.g. a Colima
     profile of its own (`colima start nf-screenshots --vm-type vz
--vz-rosetta`). A preview change that's meant shows up as a failed
     picture: rewrite it and review it like a golden (below).
5. **Packaged-app smoke**: nightly only.

**A test that needs a tool fails in CI, never skips.** A test that needs
something a machine may lack (the built test-runner jar and Java 21+ for
`apps/cli`'s `netherforge test, for real`, the pinned LuaLS) skips locally
with the reason in its name or on stderr, and with `CI` set is a failing test
naming what's missing, so CI can't pass by skipping it. `packages/api`'s
`bindings.test.ts` tests the binding model's decisions on small made-up specs
(codecs, returns, crossing shapes, handle chains, checks, refusals), not the
emitted text, which the committed generated files and `ConformanceTest` hold.

Deliberately not here: coverage gates, DOM snapshots, and Minecraft data in
the repo or in unit tests. The integration test is the one place a real
server (and the Mojang jar it patches) runs; it's downloaded at test time,
never committed.

## Goldens

```sh
UPDATE_GOLDEN=1 pnpm test        # rewrites examples/ and testdata expectations
git diff                         # review every change before committing it
```

A golden diff is a behaviour change. If you didn't expect it, it's a bug.

`apps/plugin/runtime/testdata/function-costs.tsv` (`ConformanceTest`: every API function, called
with sample arguments) and `scenario-costs.tsv` (`ApiCostTest`: vector maths, item tables, JSON,
events, spawning a scripted centity) hold what calls cost a script, in instructions of its budget
(`CostGolden`, counting every instruction). A cost that moves past `CostGolden.allowance` either
way fails: dearer is a regression; cheaper means rewriting the golden, so the next regression back
can't hide. Same `UPDATE_GOLDEN=1`.

```sh
pnpm test:screenshots --update   # rewrites the preview pictures that changed (Docker)
```

The same goes for a preview picture: look at each one before committing it.
Bumping `@playwright/test` changes the container's image too, which may
redraw them all.

`apps/editor/src/testing/fixtures/captured.nbt` is a structure a real server
saved, for the editor's NBT reader: the integration test's capture scenario
writes it again when `NETHERFORGE_IT_FIXTURES=<dir>` is set (copy
`<dir>/captured.nbt` over it after a Minecraft bump changes the format).

`AdvancementScenario` is the model for something that needs a restart: it
checks the datapack the plugin wrote, scripts granting criteria, a reload
answering `restart`, then `restart()` and the bots rejoining with their
progress. A restart replaces `editor`, so a scenario that restarts reads
`Bots(editor)` afresh (a cached one talks to the dead connection and every
request times out). The editor side runs against `MemoryBackend.testReloadRestarts()`
(vitest `state/restart.test.ts`; `e2e/advancement.spec.ts`).

## Which test for which change

| You changed…                           | Add…                                                              |
| -------------------------------------- | ----------------------------------------------------------------- |
| a validation rule                      | a case in `packages/format/testdata/invalid/`                     |
| canonical writing / a new field        | an example using it; `UPDATE_GOLDEN=1`                            |
| compiler, composition, animation maths | a `commonTest` unit test                                          |
| the bridge protocol                    | lines in `session.ndjson`                                         |
| a Lua-visible behaviour                | a runtime test running a Lua script against the fake platform     |
| a way a script could hang the server   | a case in `HangTest`: it must fail at the script's line, fast     |
| the debugger (stops, stepping, values) | a case in `DebuggerTest` (an editor over a real bridge socket)    |
| what a `Platform` capability does      | a case in its contract suite (runs on the fake and on Paper)      |
| what an editor backend command does    | a case in `contract.json` (runs on MemoryBackend and on Rust)     |
| something only real Paper can show     | an integration scenario                                           |
| something only a player can do or see  | bot steps in the integration test's bot scenario                  |
| a runtime subsystem                    | a `RuntimeService` in `ProjectSession`; `SessionTest` checks it   |
| logic in a project's scripts           | a `*_test.lua` beside it (`nf.test`), run by `netherforge test`   |
| what `nf.test` or the test runner does | a case in `ScriptTestRunnerTest` (`:plugin:test-runner:test`)     |
| what the runtime keeps on a server     | a runtime test reading `runtime.store`; the layer: `DatabaseTest` |

The fake platform's entities (`FakeWorldEntities`: mobs by UUID, players as
`FakePlayer`, which is a `FakeBody` too), inventories, boss bars and
sidebars raise what the server would: `damage` and `spawn` go through the
runtime's damage and spawn events first, `interact(player, entity)` clicks
one. `EntityTest` and `InventoryTest` are the models.
| a UI flow nothing lower can cover | a Playwright flow |

## Platform contract suites

`apps/plugin/runtime/src/testFixtures/kotlin/.../contract/` (Gradle's
`java-test-fixtures`): an abstract JUnit class per `Ops` interface
(`WorldOpsContract`, `MenuOpsContract`, …) saying what it promises: what's
set reads back, what's refused (offline players, missing worlds, unloaded
chunks), and what the runtime hears (`RecordingEvents`, bound to the
platform, records every `PlatformEvents`/`GameEvents` call and answers
"unchanged" unless a test puts the event in `cancelling`). A concrete
subclass per server says which one through `connect()`:

- **The fake**: `runtime/src/test/.../contract/FakeContracts.kt`, a fresh
  `FakePlatform` per test (`FakeContractServer`: `main` runs inline, `ticks`
  runs the fake's own later-queue and the mobs' AI).
- **Paper**: the integration project's `contract` source set is a plugin of
  its own (`NetherForgeContract`, built by `contractJar`) that loads with the
  NetherForge plugin's classes and the bots plugin, makes a `PaperPlatform`
  with the plugin's `PaperVersion`,
  keeps the world still (no time, weather, mob spawning or drops) and the
  chunks round the origin loaded, runs every suite on its own thread
  (`PaperContractServer.main` hands each call to the main thread and waits;
  `ticks` waits for real ticks), writes each result to JSON and stops the
  server. `ContractScenario` boots a server with those three and reports
  each suite's test as its own, for every adapter.

**Every suite runs on both, every test of it.** `ContractSuites` finds the
suites from themselves (the abstract `PlatformContract`s in the fixtures'
`contract` package and their `@Test` methods), so no list can forget one:
`ContractSuitesTest` holds the fake to one subclass per suite, and the
contract plugin reports its run through `ContractRun`, which names a suite's
test by the suite (`WorldOpsContract > …`) and adds every expected test that
never ran as `MISSING` (its first Paper run found `PauseOpsContract` missing
from `PaperContracts.ALL`). `ContractScenario` fails on anything but a pass:
a failure, an abort, a skip, or a missing test. A test only one server can
run says so and why, `@OnlyOn(ContractTarget.FAKE, "…")` (disabled on the
other, which the JUnit parameter `netherforge.contract.target` names: the
contract plugin sets `PAPER`); `ContractScenario` reports it as skipped, with
the reason. `ContractRunTest` checks the report itself.

**What a player receives is checked on their client.** Players are bots on
both servers, and a bot keeps what its client was sent:
`screen(player)` (its `BotState`: boss bars, sidebar, dialog, packs, player
list with each entry's name, order, listing and the line under its name
tag), and its numbered events (`mark(player)` then `sentSince` or
`awaitSent`: sounds and stops, particles, boss bars shown and hidden, packs).
`awaitScreen` waits for the screen to show something; `settled()` lets a few
ticks pass before asserting a bot was _not_ sent something. So the particle,
sound, boss bar, sidebar, dialog, pack, team and player list suites pin what
reaches a player, not only what an Ops call answers; the fake bots model
Paper's ranges (particles 32 blocks, 512 forced, of particles the game has;
a world's sound 16 blocks, times its volume above 1). A new thing players
receive gets a `BotEvent` or `BotState` field in format's bot protocol,
recorded by the Paper bots (`Bot.kt`, through `BotProtocol` where versions'
packets differ) and by `FakeBots.sent`, and a contract case.

Runtime tests may read the fake's call logs (`particles.sent`,
`sounds.played`, `resourcePacks.sent`: what the runtime asked) and the fake's
state that a contract pins on both servers through the screen above
(`dialogs.showing`, `sidebars.showing`, `bossBars`, `teams.belowNames`); a
read of anything else the fake models goes through the `Ops` interface
(`playerList.isListed`, not its `unlisted` map).

The contract server has no project, but it has biomes of its own: the contract plugin's `ContractBootstrap` (a
Paper bootstrapper, with a `bootstrap` dependency on NetherForge for its classes) builds `ContractBiomes` (a featureless
one, one with every field, one with features) through format's start-up datapack for the server's data pack format
and registers it, so `PaperWorldGeneratorsTest` checks what the server makes of a project's biomes on every version. Its dimension types (`deep`, -128 to 383, and `odd`, every field set) are registered the same way: `PaperWorldGeneratorsTest` makes a world of each, checks the limits, the generator bound to them and blocks at both ends, and loads one again.

`PaperDatapackCheckTest` is Paper-only too: the server jar's own worldgen files, passed through as a project's datapack,
are clean through format's checks with the server's registries, and every reference `WorldgenReferences` finds in them
is in the registry it says, on every supported version.

`PaperNoteBlocksTest` is the one Paper-only suite besides `PaperGameEventsTest`: the vanilla note blocks' part
the adapter plays once custom blocks freeze note blocks (tuning, the instrument from below or a head above, redstone,
a carrier state's silence), observed through a `NotePlayEvent` listener; it runs before the last suite.

Other plugins (Vault, PlaceholderAPI) have no contract suite or integration
scenario, since the test server has neither jar: the runtime's side is
`InteropTest` against `FakePlatform` (`plugins.enable`, `vault.register()`,
`placeholders.request`), and the adapter is `PaperInterop.kt`, compiled
against the real APIs only.

`BotActionsContract` is the one for what a bot does and the events that
makes (`PaperGameEventsTest` adds every listener bots can't raise); the fake
must match Paper on all of it: bots act, game mode, kick, level and world
load events, death XP, `FakePlatform.tickWorld()` for pickups and eating.

Players in the suites are **bots** (`PlatformContract.join`), on both. Helpers
undo what a test made (players leave, spawned entities go, placed blocks are
put back, `afterwards { }` for the rest), since the Paper suites share one
server; filter `events` by the entity or player a test made, as a real
server raises its own events too.

When the fake and Paper disagree, **Paper is the truth**: fix the fake
(`:plugin:testkit`), unless the adapter breaks what the interface's doc says,
then fix the adapter. A new `Ops` method gets a case in its suite; a new
`Ops` interface gets a suite and a subclass in both `FakeContracts.kt` and
`PaperContracts.kt`.

```sh
node tools/gradle.mjs :plugin:runtime:test --tests '*FakeWorldOpsTest*'                 # one suite on the fake
node tools/gradle.mjs :plugin:integration:integrationTest-26.3 --tests '*ContractScenario*'   # every suite on Paper 26.3
```

The Paper run's server log is `apps/plugin/integration/build/integration/<minecraft>/server/contract-test.log`
(the other scenarios' is `integration-test.log` beside it).

## Commands

```sh
pnpm test                 # everything fast: Gradle check, vitest, cargo test
node tools/gradle.mjs :format:allTests
node tools/gradle.mjs :plugin:runtime:test
pnpm test:integration   # every version, the `full` scope
node tools/gradle.mjs :plugin:integration:integrationTest-26.2 -Pnetherforge.integration.scope=pr   # what a PR runs on 26.2
pnpm test:e2e
pnpm test:screenshots     # the previews' pictures (Docker); --update rewrites them
```

## CI

`.github/workflows/ci.yml` runs on every PR and every push to `main`, and the
release workflow calls it on the tagged commit (see the release skill):

| Job         | Runner                 | What                                                                                                                                                                                                                                                 |
| ----------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| check       | ubuntu, macOS, Windows | `pnpm build`, lint, `pnpm test`. rustfmt and clippy on Linux only (`node tools/check.mjs lint --skip rust` elsewhere); the Rust tests everywhere.                                                                                                    |
| integration | ubuntu                 | One job per Paper adapter (listed from `apps/plugin/paper-*`), each `integrationTest-<minecraft>`, its Paper and Mojang jars cached per version and pinned build. PRs in the `pr` scope, `main` and releases `full`. Test counts in the job summary. |
| e2e         | ubuntu                 | `pnpm test:e2e` (Chromium), then `pnpm test:screenshots` (the previews' pictures, in the Playwright container).                                                                                                                                      |
| tauri-build | ubuntu                 | `tauri build --debug --no-bundle`.                                                                                                                                                                                                                   |

`nightly.yml`:

| Job         | Runner                                                | What                                                                                                                                              |
| ----------- | ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| integration | every version on ubuntu; the newest on macOS, Windows | `integrationTest-<minecraft>` in the `full` scope, then (Linux) the `quarantine` scope in a step that can't fail the night (`continue-on-error`). |
| editor      | ubuntu, macOS, Windows                                | `pnpm test:e2e`, the bundle, and the packaged-app smoke test (not on macOS).                                                                      |
| report      | ubuntu                                                | A failure of either opens an issue labelled `nightly`.                                                                                            |

A newer push to a PR cancels its older run; runs on `main` and tags always
finish. Every job has `timeout-minutes`. A failed integration job keeps each
scenario's server log (`build/integration/<minecraft>/server/*.log` and
`logs/`), the JUnit XML and the HTML report as the artifact
`integration-<minecraft>[-<os>]`; a failed e2e run keeps Playwright's
`test-results/` (each failed test's trace) and `playwright-report/` as
`e2e-results`. `.github/actions/junit-summary` writes a folder of JUnit XML's
counts and failed tests into the job's summary. `docs.yml` builds the docs on
every PR and push that touches them and deploys from `main` only when the
repository has GitHub Pages (see the release skill).

**Flaky tests are fixed or quarantined with a linked issue the same day**,
never retried silently. Quarantining one: open an issue (what fails, a link to
the run), then tag the test with it:

- **Integration (Kotlin)**: `@Quarantined("https://github.com/<owner>/<repo>/issues/<n>")`
  (the runtime test fixtures' annotation, below, the JUnit tag `quarantine`) on the
  scenario class, or on a step no later step builds on; its `QuarantineTest`
  holds the link to an issue's URL. Every normal run leaves it out; nightly runs
  `-Pnetherforge.integration.scope=quarantine` on its own, without failing,
  so a pass there says the fix worked.
- **The runtime's and the test runner's tests**: `@Quarantined(issue =
"https://github.com/<owner>/<repo>/issues/<n>")` (runtime test fixtures,
  `dev.netherforge.plugin.Quarantined`, the same `quarantine` tag) on the test
  method or class. Their `test` tasks leave it out; `quarantinedTest` runs
  only it (`node tools/gradle.mjs :plugin:runtime:quarantinedTest`).
  `QuarantineTest` (one in each) fails on a link that isn't an issue's URL
  and on the tag put on by hand, without one.
- **Other unit tests (Kotlin, JUnit 5)**: `@Tag("quarantine")` with the issue's
  URL in a comment above it.
- **vitest**: `it.skip('…', …)` (or `describe.skip`) with
  `// quarantined: <issue URL>` above it.
- **Playwright**: `test.fixme('…', …)` (or `test.fixme()` inside the test)
  with `// quarantined: <issue URL>` above it.

The fix takes the tag (or `skip`/`fixme`) off and closes the issue.

Every third-party action is pinned to a full commit SHA with its version in
a comment (`uses: actions/checkout@<sha> # v5.1.0`), and every workflow and
job declares least-privilege `permissions:`. To bump one, resolve the new
tag's commit (`gh api repos/<owner>/<repo>/commits/<tag> --jq .sha`) and
change the SHA and the comment together. Check workflow edits with `actionlint`
(it isn't part of `pnpm lint`).

## Dependency locks

The Gradle build checks everything it downloads:

- **Locking.** Every configuration's versions are pinned in a
  `gradle.lockfile` per project (plus `tools/gradle/buildscript-gradle.lockfile`
  and `settings-gradle.lockfile` for plugins), in strict mode.
- **Verification.** `tools/gradle/gradle/verification-metadata.xml` holds
  the SHA-256 of every artifact and POM, including the Node distribution
  Kotlin/JS tests with, for every OS. Some snapshots are trusted by name,
  since no checksum can be pinned: paperweight's `codebook-cli` and the
  Paper server's `spark-api` and `velocity-native` (republished in place),
  and Minecraft 1.21.11's `paper-api` and `dev-bundle`, which Paper only
  published as snapshots (Gradle doesn't record the file a snapshot resolves
  to). Each `<trust>` says why.
- **Kotlin/JS's npm packages** are pinned in
  `tools/gradle/kotlin-js-store/package-lock.json`; a build fails if they'd
  change (`kotlinStorePackageLock`).
- **The Gradle distribution** is pinned by `distributionSha256Sum` in
  `gradle-wrapper.properties`.

After adding or bumping a dependency or plugin (a build fails with "not part
of the dependency lock state" or "Dependency verification failed"), run
`pnpm deps:lock` (`tools/gradle-lock.mjs`) and review the diff: every new
artifact or checksum is code the build will run. It empties the verification
metadata's components and records them afresh, with `--refresh-dependencies`
so a warm local cache can't hide a POM that a fresh CI runner fetches; the
trusted snapshots in `<configuration>` stay. To upgrade Gradle:
`node tools/gradle.mjs wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`
(the `.sha256` next to the zip on services.gradle.org), then `pnpm deps:lock`.
The npm lockfile carries the project version, so `node tools/release.mjs
<version>` rewrites it with the rest.
