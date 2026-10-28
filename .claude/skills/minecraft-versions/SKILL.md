---
name: minecraft-versions
description: How NetherForge supports multiple Minecraft versions - the project's target version, the supported range (the last 1.21.x and every 26.x), paper-common, paper-internals and one adapter per version, the build-logic convention plugin, the bots (in the plugin jar), the feature table and gating a feature by version, and what to do when a new Minecraft version ships. Read before bumping Paper, adding a version adapter, or using a game feature that older versions lack.
---

# Minecraft versions

NetherForge supports **the last 1.21.x release (1.21.11) and every 26.x**:
1.21.11, 26.1.2, 26.2 and 26.3 today.

## The pieces

- **A project names its target**: `"minecraft": "26.3"` in `netherforge.json`.
  The editor runs that Paper version with the plugin jar built for it;
  validation uses that version's data; the plugin refuses a project for
  another version (`ServerInfo.supportedTargets`, from `api-version`).
- **`MinecraftVersion.OLDEST_SUPPORTED`** (`packages/format/.../game/MinecraftVersion.kt`)
  is the oldest target a project may name (`project.minecraft-old`).
- **The feature table** (`packages/format/.../game/Features.kt`, `FeatureTable.CURRENT`)
  is every feature NetherForge uses that arrived after the oldest supported
  version, with the version it arrived in. It's data; see "Using a feature
  older versions lack". It's empty today: everything NetherForge uses works
  from 1.21.11 on (spawn limits and intervals per spawn category, W3.3, are
  the same `World` calls on every supported Paper, so nothing is gated), and where a version only does something differently (where
  a world's files are, a packet's shape) its adapter does it its own way.
  The `equippable` component's `asset_id` and the resource pack's `equipment/`
  asset folder (custom armour looks, `EquipmentDef`) are among them: both are 1.21.4+,
  and only 1.21.2-1.21.3 used `models/equipment`, below the supported range.
- **Datapack JSON** can differ by version where the feature table has nothing to gate (the feature exists
  everywhere, written another way): a start-up datapack kind asks the server's data pack format
  (`DatapackContext.formatAtLeast`), never the project's target. Biomes are the case: 1.21.11 to 26.2 keep a
  biome's mobs in `spawners`/`spawn_costs`, 26.3 (data pack format 121) in the `natural_mob_spawns` attribute
  (`BiomeJson.SPAWNS_ATTRIBUTE_FORMAT`). Read the shape off each server jar's own `data/minecraft/...` files (patch
  the Paper jar with `-Dpaperclip.patchonly=true`), and give a new version's server the contract suite's biomes.
- **A project's own datapacks** (`datapacks/<id>/`, W5.5) are gated the game's way, by data pack format: `pack.mcmeta`'s
  range and overlays, which format applies for the server's format as it builds the start-up datapack. A new
  version's worldgen changes are the project's to cover with an overlay; for NetherForge it means running
  `PaperDatapackCheckTest` (the rules in `WorldgenReferences` over the new server's own files) and adding the
  registry's new name to a rule's targets when one moved (26.3: `configured_feature` → `feature`).
- **Dimension types** (`DimensionTypeJson`, read off each jar's `data/minecraft/dimension_type/` and `DimensionType` codec): every
  supported version keeps the game's rules (beds, respawn anchors, piglins, raids, ultrawarm) and the sky's colours as
  environment attributes, and a type of `{}` writes what that jar's `overworld.json` has (its cave sounds and music
  too); 26.1 (data pack format 101, `CLOCK_FORMAT`) requires `has_ender_dragon_fight` and has `default_clock` and the
  overworld's `ambient_light_color`; 26.3 (121, `STRAW_BED_FORMAT`) renamed a bed rule's `explodes` to `destroy_on_use` and added the
  `straw_bed_rule` attribute (an older server refuses an unknown attribute). Giving a new world one is past Bukkit's API:
  `PaperVersion.prepareDimension` saves the world's generation settings with the type before `createWorld` (each
  adapter's `Mojang`: `world_gen_settings` saved data from 26.1, `level.dat` on 1.21.11, written field by field as
  `PrimaryLevelData.setTagData` would, which needs the world to exist), mirroring that version's `CraftServer.createWorld`; a new version re-reads both and gets the contract's dimension case.
- **Note block states** differ by version (26.x has more instruments than 1.21.11): custom blocks are
  held in them, and `BlockCarriers` reads the pool from `GameData.block`, so a new version needs nothing
  (the default instrument's column is vanilla's, the rest, rarest first, the blocks'). `PaperVersion` has the
  adapter's few server-internal pieces for them (`disable-noteblock-updates`, `noteInstrument`, `playNote`).
- **Game facts** (blocks, items, shapes) come from the game at runtime. See
  the game-data skill. A new version needs no data release.

## The plugin, per version

```
apps/plugin/runtime/          the runtime: no Paper types, ever (plugin-runtime skill)
apps/plugin/paper-common/     :plugin:paper-common  the adapter on Paper's API, compiled against the
                              oldest supported API (paper-api in the version catalog): almost all of it
apps/plugin/paper-internals/  not a project: Mojang-named server code every adapter compiles against its
                              own server (src/main: registries, goal priorities, the PaperVersion;
                              src/bots: the bots)
apps/plugin/paper-<mc>/       :plugin:paper-<mc>  one per supported version: its build file (the Paper
                              build) and what its server says its own way (Mojang.kt, Protocol.kt)
apps/plugin/integration/      :plugin:integration  every scenario against every adapter (testing skill)
tools/gradle/build-logic/     the convention plugins: netherforge.kotlin-jvm, netherforge.paper-adapter
```

- **paper-common** is the whole Platform on the Paper API, the plugin's main
  class (`NetherForgePlugin`) and its `config.yml` and `paper-plugin.yml`
  templates (`src/plugin/`). It's compiled against the oldest API, and every
  adapter compiles its sources again against its own version's API
  (`compilePaperCommonKotlin`, part of `check`), so an API a newer version
  removed is a compile error, not a `NoSuchMethodError` on a server.
- **`PaperVersion`** (paper-common) is what one version does its own way:
  goal priorities, the registries (`ServerRegistries`), where a world's files
  are (`WorldStorage`: `DimensionStorage` from 26.1, `WorldFolders` before).
  Each jar has exactly one, found through `ServiceLoader`
  (`META-INF/services`, in paper-internals). Add to it rather than branching
  on the version in paper-common.
- **paper-internals** is the code that reaches past the Paper API into the
  server (Mojang-named, through paperweight-userdev): `MojangRegistries`
  (Paper's registry API covers a chosen few registries and can't list them),
  `goalPriorities` (Paper's goal API has none), and the bots (no API makes a
  player without a client). It's written once against two small interfaces,
  `ServerInternals` (main) and `BotProtocol` (bots), which each adapter's
  `Mojang` and `Protocol` objects answer for its server: a renamed accessor,
  a record that was a class, a packet that was split (26.3's attack and punch
  packets, 1.21.x's interact packet that carried attacks; `ClickType` before
  `ContainerInput`). Everything else in it compiles as it is on every version.
- **An adapter** is `apps/plugin/paper-<mc>/build.gradle.kts`:

  ```kotlin
  plugins { id("netherforge.paper-adapter") }
  paperAdapter { paper = "26.3.build.181-stable" }
  ```

  The folder name is the one place its Minecraft version is written: Gradle
  includes every `paper-<version>` folder, and the convention plugin derives
  the jar names, `paper-plugin.yml`'s `api-version`, `runServer`'s version and
  the Java it targets (21 before 26.1, 25 from it) from it. `paper` is the
  Paper build: paper-api and its dev bundle for compiling, and the server
  build the integration test downloads (`.build.181`), which the plugin jar
  names in `META-INF/netherforge/paper.properties` so the editor's dev server
  runs that build too, never a newer, untested one. Paper published 1.21.x
  as snapshots, so `paper-1.21.11` also says `serverBuild = 132`.
  `sources = "26.3"` makes an adapter compile another's `src/` (its
  `Mojang`/`Protocol`), for a version whose server says everything as that
  one's does: then the folder is nothing but its build file. A new adapter
  joins `pnpm test:integration` by itself, and runs in parallel with the
  others (testing skill).

- **The build runs with Gradle's configuration cache**
  (`tools/gradle/gradle.properties`), which is also what runs the versions'
  integration tests at once. Build logic keeps to its rules: a task's
  actions hold no `Project`, `Configuration` or script reference (copy a
  script value into a local first), and a path known only once another task
  has run reaches a test JVM through an argument provider
  (`SystemPropertyPath` in build-logic), not a `doFirst { systemProperty(…) }`.
  A cache problem fails the build and names the task.

- **Jars, per version** (`:plugin:paper-<mc>:assemble`, in `build/libs/`):
  `NetherForge-<v>-paper-<mc>.jar` (the plugin: runtime, format, Kotlin and
  the adapter shaded in, the bots included: `PaperVersion.bots` makes them,
  since they're paper-internals code). The editor installs it into its dev
  servers (the tauri-backend skill's plugin jars), and the release attaches it
  (release skill).
- **Bytecode**: everything shared (format, runtime, paper-common) targets
  Java 21, since a 1.21.11 server may run on it (`netherforge.kotlin-jvm`);
  a 26.x adapter targets 25. The editor's dev servers always run Java 25.

## Using a feature older versions lack

1. Add it to `FeatureTable.CURRENT`: an id, the version it arrived in, and
   what it is in a problem's words. `FeaturesTest` checks nothing in it
   predates `OLDEST_SUPPORTED`.
2. Validators report using it against a target that lacks it:
   `ctx.requireFeature("<id>", "$.path")` in a kind's `validate`
   (`project.feature`, an error). Tests hand `Projects.load` a table of their
   own (`features = FeatureTable(...)`): that's the table's test hook, since
   the real one may hold nothing a test could rely on.
3. The editor hides or disables it for such targets: `FEATURES` and
   `OLDEST_MINECRAFT` are in format's generated constants.
4. In the Lua API, mark the function, event or option field `since: "<id>"`
   in the spec (`packages/api/src/spec/`): the generator looks the version up
   in `FEATURES`, shows it in the docs and stubs, and lists it in
   `VersionGates.kt`; on an older server the runtime hands the prelude that
   gate, and calling the function, listening for the event or setting the
   option is an error naming the version (`VersionGateTest`). An id that
   isn't in the table fails `pnpm generate` and the spec test. The same gates go
   to the editor (`generated/luals/gates.json`): its per-project copy of `nf.lua` marks
   a function newer than the project's `minecraft` `---@deprecated`, so a script is
   flagged before it's run on an older target.
5. If the adapter needs a different call on older versions to do it, that's
   `PaperVersion`'s (or an older adapter's `Mojang`/`Protocol`), never a
   version check in paper-common.

## Screens' maps and frames

The map packet (`ClientboundMapItemDataPacket` with a `MapPatch`), `MapColor`'s table and fixed item frames
(`ItemFrame`/`GlowItemFrame(level, pos, direction)`, `fixed` set before `addFreshEntity`, which skips Bukkit's
wall check) are the same on 1.21.11 and every 26.x, so `Version` (paper-internals) does them once
(`PaperVersion.sendMap`, `mapColors`, `hangFrame`); a version that moves one gets its own in its `Mojang`.

## A new Minecraft version ships

1. **A new build of a supported version**: bump `paper` in its adapter's
   build file (Renovate opens these as their own PRs), `pnpm deps:lock`, and
   run its scenarios: `node tools/gradle.mjs :plugin:integration:integrationTest-<mc>`.
2. **A new 26.x minor**: a folder `apps/plugin/paper-<mc>/` with its build
   file naming the Paper build (and `sources = "<newest>"` when its server's
   internals didn't move), then `pnpm deps:lock` and the integration run. The
   build compiles paper-common and paper-internals against the new server, so
   what changed shows up as compile errors:
   - in paper-common: the Paper API changed; adapt through `PaperVersion`;
   - in paper-internals: give the new adapter its own `Mojang.kt`/`Protocol.kt`
     (copy the newest and change what moved), or widen `ServerInternals`/
     `BotProtocol` for something new that differs.
     What compiles can still behave differently. The integration scenarios
     say: the bot scenario for joining, moving, clicking and reading the
     screen (the bots use the server's own connection, packets and dialog
     model); `MobScenario`'s goals step for goal priorities; the game data
     scenario for registries; the reload scenario for commands
     (`PaperCommands` removes Brigadier nodes by reflection, since the
     registrar is only offered in the `COMMANDS` lifecycle event).
3. Gate anything new it brings that NetherForge uses (above).
4. **Dropping the oldest version**: delete its adapter folder, raise
   `MinecraftVersion.OLDEST_SUPPORTED` and `paper-api` in
   `tools/gradle/gradle/libs.versions.toml` together, drop table entries that
   now predate it, and change `PaperVersion` implementations only it used.

Adapters with a dev bundle take a while on first build (paperweight sets up
each version's server once, cached in the Gradle user home).
