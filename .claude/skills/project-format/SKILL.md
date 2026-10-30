---
name: project-format
description: How NetherForge project files are modelled, parsed, written canonically and validated in packages/format/. Read before adding or changing anything a project file can contain (centity.json, netherforge.json, new resource kinds), a validation rule, or the canonical writer.
---

# The project format

The spec users read is `docs/format/`. The implementation is `packages/format/`
(Kotlin Multiplatform, JVM for the plugin and JS for the editor). This guide
is how the implementation is put together and how to change it.

## Where things are

| File                                                       | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `packages/format/src/commonMain/.../json/CanonicalJson.kt` | strict parsing (with path + line), the canonical printer, number formatting                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `.../project/Kinds.kt`, `KindSpec.kt`                      | the registry of resource kinds (`Kinds.all`) and `Kinds.classify`; `KindSpec` (layout, main file, `validate`/`crossCheck`, compiler, script, template), `ResourceContext`/`KindContext`, `Loaded`                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `.../<kind>/<Kind>Kind.kt`, beside the kind's model        | one `KindSpec` per kind, in its model's package: `centity/CentityKind`, `menu/MenuKind`, `dialog/DialogKind`, `item/ItemKind`, `recipe/RecipeKind`, `loot/LootTableKind`, `block/BlockKind` and `GameBlockKind`, `particle/ParticleEffectKind`, `cutscene/CutsceneKind`, `resourcepack/ResourcePackKind`, `module/ModuleKind`, `world/StructureKind` (with `StructureGenerationKind`), `BuildKind` and `MapKind`, `biome/BiomeKind`, `dimensiontype/DimensionTypeKind`, `terrain/TerrainKind`, `screen/ScreenKind`, `ScreenFontKind` and `VideoKind`, and the rest the same way. `project/` keeps what every kind is built on (`KindSpec`, `Kinds`) and the project as a whole |
| `.../project/DocumentKind.kt`                              | `DocumentKind` (serializer, `$schema` ref, `canonical()` ordering), and the two project documents: `ManifestKind`, `DefaultFontKind`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `.../project/Project.kt`, `ProjectCache.kt`                | `ProjectSource` (text, and `image` pixel facts), `Projects.load` → `ProjectSnapshot` (every kind's resources, by the registry, and the packages it depends on), `ProjectCache` (what a load keeps for the next)                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `.../project/Packages.kt`, `PackageFiles.kt`               | packages: `PackageSources` (where dependencies are found), `Packages.resolve` → `Resolution`, the lock check; `LockFile`/`PackageSource` (`netherforge.lock`), `BundleManifest`, `PackageHash` (the content hash)                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `.../ref/`                                                 | references: `ResourceRef`/`ResourceKey` (the syntax), `Ref`/`RefKind` (what a field names), `RefWalker` (every `@Ref` and glyph tag in a document), `ReferenceIndex` (what can be named, by namespace; checks, usages, rename)                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `.../Problem.kt`, `.../ProblemCodes.kt`                    | `Problem` (with `related` locations), `ProblemSink.report`, and the catalogue: every `ProblemCode` (code, severity, summary, spec page)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `.../project/Names.kt`                                     | id, node-name and relative-path rules                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `.../centity/`, `.../menu/`, `.../dialog/`, `.../item/`    | the centity model, validator, compiler, compiled form, `Composer` (world matrices, posing); menus, dialogs, `ItemDef`, project items (`ItemFile`, `ProjectItems`: resolving, restyling, the look hash)                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `.../resourcepack/`, `.../text/`                           | resource packs (`PackSounds`: `sounds/*.ogg` as events), MiniMessage: `MiniMessagePass` (styled characters, glyph tags and line breaks; the editor draws it through `styleText`), `TextMetrics` (editor previews, estimates, measured over that pass), `TextWidth` (exact or null, for the server) and `DefaultFontFile` (`fonts/default.json`, written by the editor)                                                                                                                                                                                                                                                                                                         |
| `.../loot/`                                                | loot tables: model (`LootRange`, entries and conditions as sealed `type`s), validator, `LootRoller` (a roll from a seeded `Random`, the same on JVM and JS; the server's and the editor's preview's)                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `.../block/`                                               | custom blocks: `BlockFile` (+ `BlockSounds`, `BlockTool`), `BlockValidator`, `BlockCarriers` (which note block state each block is held as: the pool, the plan)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `.../dimensiontype/`                                       | dimension types: `DimensionTypeFile` (build limits, light, sky, colours, the game's rules; `worldHeight`), `DimensionTypeValidator` (the game's height rules), `DimensionTypeJson` (the game's `dimension_type` JSON for the server's data pack format)                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `.../biome/`                                               | biomes: `BiomeFile` (climate, colours, particle, sounds, spawns, spawn costs, features by step), `BiomeValidator`, `BiomeJson` (the game's `worldgen/biome` JSON for the server's data pack format)                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `.../noise/`, `.../terrain/`                               | terrains: `FastNoiseLite` (the port), `NoiseDef`, `TerrainSeeds`; the model (`TerrainFile`), `TerrainValidator`, `TerrainCompiler` → `CompiledTerrain`, `TerrainGenerator` (the chunk pipeline), `TerrainPreview`                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `.../particle/`                                            | particle effects: model, validator, compiler, `EffectSampler` (each tick's spawns, seeded; server and preview)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `.../cutscene/`                                            | cutscenes: model (`CutsceneFile`, `Camera`, `RotationKey`, `Cue`), validator, compiler, `CompiledCutscene`/`CameraPath` (the camera's pose at a time; server and preview)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `.../workspace/Workspace.kt`                               | `netherforge-workspace.json` (above projects, not in one): `WorkspaceFile`, `WorkspaceKind`, `Workspaces` (patterns, `expand`, `check` with the `workspace.*` codes, tested in `WorkspaceTest` since goldens load projects); JS: `checkWorkspace`, `isWorkspaceServerName` (`WorkspaceExports.kt`). `NetworkUses.kt`: the names scripts give `nf.network` as literals (`of`, over `LuaStrings.tokens`; `send_player`'s server, `channel`'s and `map`'s name) and `check`, `workspace.unknown-server` for a server the workspace's network lacks; JS `networkUses`, `checkNetworkUses` (the editor's Problems and LuaLS names, the CLI's workspace `check`)                     |
| `.../bridge/Bridge.kt`                                     | the dev bridge protocol (see the hot-reload skill)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `src/jsMain/.../*Exports.kt`                               | the editor's JS surface: JSON strings in and out, one file per area (`Exports.kt`: documents and the project; `PackageExports`, `RefExports`, `PreviewExports`, `NewFileExports`, `TextExports`; `ExportSupport`, what they share)                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `src/jvmMain/.../codegen/Contract.kt`                      | generates JSON Schemas + `types.ts` from the serializers (`:format:generateContract`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

## The rules that shape everything

- **One registry of kinds.** `Kinds.all` is the only list of resource kinds.
  Loading, the JS exports, the editor's kind table (`KINDS` in the generated
  constants), the CLI, the plugin's hot reload and the golden tests all read
  it, and which resource a path is is always `Kinds.classify(path)` (the
  editor's and CLI's through the `classify` export). Never match folder
  names or file names against a kind by hand anywhere else.
- **One canonical form.** `DocumentKind.write` = `canonical()` (sort keyed
  maps, sort keyframes, set `$schema`) then `CanonicalJson.write`. Never
  hand-format JSON anywhere else, in any language.
- **Numbers print identically on JVM and JS.** `CanonicalJson.formatNumber`
  re-lays out the shortest round-trip digits itself. The model uses `Double`,
  never `Float` (Kotlin/JS has no float32).
- **Keyed objects, not lists, for anything people add to concurrently.**
  Nodes, animations, tracks. That makes names unique by construction and keeps
  git merges clean. Lists are fine for ordered data (keyframes, boxes).
- **Strict parsing.** Unknown keys are errors. Paths are normalised to
  `$.nodes.root` / `$.nodes["a b"]` via `CanonicalJson.childPath`; use it
  whenever you build a path in a validator.
- **Parse errors vs. problems.** A file that fails to parse is absent from the
  snapshot. A file with semantic errors is present (the editor shows it) but
  isn't compiled (nothing runs it). The compiler `require`s a valid input:
  a failure there is our bug, not the user's.
- **Problems have stable `code`s, from one catalogue.** Every problem is a
  `ProblemCode` in `ProblemCodes` (code, severity, a one-line summary, the spec
  page that explains it), so a call site can't make one up:
  `sink.report(ProblemCodes.RESOURCE_PACK_KEY, message, path)`. A new rule adds its
  code there; `UPDATE_GOLDEN=1 pnpm test` rewrites `docs/format/problems.md`
  (`ProblemCatalogueTest`) and the editor gets `PROBLEM_CODES` in the
  generated constants. The golden test fails on a code that isn't
  registered. Tests and the editor's filters key on codes: don't rename one
  casually.
- **One reference syntax.** A file names another resource as `id` in the
  project's own namespace (`netherforge.json`'s required `namespace`) or
  `ns:id` in another's; a resource pack's entries are `<pack>/<key>` (`ui/coin`,
  `acme:ui/coin`), because every resource pack is built into the project's namespace
  under its id. A field that refers to something is a `ResourceRef` marked
  `@Ref(RefKind.X)` (a file inside the resource is a `String` marked
  `TEXTURE`, `SOUND_FILE` or `SCRIPT`); text a player reads is
  `@MiniMessage`, whose `<glyph:…>` tags are references too. Never parse or
  check a reference by hand: the walker finds it, the loader checks it, and
  usages and renames follow it.
- **Ids are folder names**, `Names.isId`. Never put an `id` inside a file.
- **Group folders only sort.** `(name)` folders (`Names.GROUP`) may sit between a kind's folder and a
  resource's (`items/(weapons)/(wands)/ruby_wand/`), and the id is still `ruby_wand`: an id is the kind's
  whole (`project.duplicate-id` past the first, in sorted group order with no group first), a bad group name is
  `project.group`, and `KindSpec.groupable` is false for resource packs only (a pack's id names everything in it
  and where its files go; `project.group` too). `Kinds.classify` reads the group from the path alone
  (`ClassifiedPath.group`, `PathRole.GROUP` for a group folder itself). **So a resource's location doesn't follow
  from its id**: `locationOf`/`pathOf`/`fileOf`/`companionPathOf` take a `group`, and everything that needs an
  existing resource's files asks where it is: `ProjectSnapshot.placeOf(kind, id)` (a `ResourcePlace`: `location`,
  `path`, `file(..)`, `companionPath`; a package's id `ns:id` at its package path), `KindContext.placeOf`,
  `ResourceContext.group`/`pathIn(kind, file)`, `Loaded.group`, the outline's `groups`. A bare `pathOf(id)` is
  only right for where a _new_ one goes or in a message about a missing one. Nothing names a group, so moving
  a resource changes no reference. Cases: `GroupsTest`, `invalid/groups`.
- **One format version, no migration.** `netherforge.json`'s `formatVersion`
  must be `FormatVersion.CURRENT` (1). Anything else is
  `project.format-version` (`Projects.formatProblem`, naming the version);
  the plugin refuses to run such a project and the editor to open it
  (`projectRefusal` in the JS exports). Only `formatVersion` is read from such
  a manifest (`Projects.formatVersionOf`), so one missing keys this format
  requires still says which format it's in. A change that makes old projects
  mean something different bumps it.
- **A resource has at most one script**, a top-level `script` (`ScriptDef`:
  `file`, `budget`), validated by `Rules.script`. Nodes, slots and buttons
  never carry one. Other `.lua` files in a centity's, menu's, dialog's or
  item's folder, at any depth, are the files beside the script, which it
  `require`s: valid project files, named as module files are
  (`Names.LUA_FILE`), else `script.file-name` (a warning: `require` can't
  reach it; the script itself is exempt, its JSON names it).

## References

- **`ResourceRef(text)`** is a reference as written; `resolve(home)` gives
  the `ResourceKey(namespace, path)` it names (or null when it isn't shaped
  like one); `RefKind.isPath` narrows the shape per kind (an id, or a resource pack
  and a key; a sound's key may hold `/`). `ResourceKey.relativeTo(home)` is
  how a file writes it back.
- **`RefWalker`** walks a document's JSON beside its serializer's
  descriptors: `find` yields `FoundRef(kind, path, text, start?, end?)`, and
  `rewrite` replaces the ones a function answers for (a glyph tag becomes
  `<glyph:new>`, or goes for `""`; `skip` leaves values of the serial names
  it lists whole, as the runtime skips a definition's items). It works on
  JSON so the editor can hand it a document with errors in it, and a rename
  touches nothing else. `GlyphTags.rewrite` is the same for one string. `Ingredient`'s
  descriptor is the object form's, so `{ "item": … }` is found like any field.
- **`ReferenceIndex`** (in `KindContext` and the snapshot) is keyed by
  namespace: the project's own (`home`), and each package it depends on with
  what that exports (see "Packages"). `check(kind, text)` is the one message for a
  broken reference (the plugin's Lua errors use it too, with the index of
  the calling script's package, `snapshot.referencesOf(namespace)`);
  `unknownNamespace` and `notExported` are its words for another package's
  name, which `ProjectSnapshot.nameable(from, kind, key)` uses for any
  resource kind, not only those files can name (the runtime checks a
  script's menus, centities and particle effects with it); `usagesOf(target)`
  and the static `rename(kind, file, text, home, target, to)` take a
  `RefTarget`: a `Resource` (a resource pack stands for every entry in it), a
  `ResourcePackEntry`, or a `File` (a path, or a folder of them) inside a resource.
  A file reference resolves to a project path, so renaming a texture
  rewrites only the resource pack that names it.
- **JS**: `findUsages(filesJson, targetJson)`, `renameRefs(path, text,
namespace, targetJson, to)` and `resolveReference(kind, text, namespace)`;
  the editor's renames, delete prompt and resource pack previews use them and
  nothing else. `qualifyRefs(path, text, namespace)` writes every
  reference in a document in full, for drawing a package's resource beside
  the project's (the editor's `useProjectItem`). `GoldenTest` writes every reference the examples make to
  `testdata/references/<example>.json`, so one that isn't marked shows up.

## Packages

A package is a project another depends on (`docs/format/packages.md` is the
spec). `netherforge.json` has `dependencies` (namespace → `Dependency`: one
source, `path`, or `git` with an optional `rev`; a registry source comes as
another field) and `exports` (kind folder → ids).

- **Loading.** `Projects.load(source, game, packages: PackageSources)`. The
  host answers `open(namespace, request)`: a `PackageRequest.Folder(location)`
  (the package's folder relative to the root's, `..` resolved by
  `Packages.join`) or a `PackageRequest.Git(url, rev, commit)` (`commit` the
  lock's pin while the lock says the same `git` and `rev`, else null: resolve
  `rev` now), with an `OpenedPackage(source, hash?, commit?)` (a git one says
  which commit it opened) or `PackageMissing(reason?)` (`package.missing`
  for a folder, `package.git` with the reason for a repository);
  `PackageSources.NONE` (the default) opens nothing. A bundle's sources
  answer by namespace and set `checksLock = false`. A git package's location
  is `git:<commit>` (`Packages.gitLocation`/`commitOf`), its checkout in the
  host's package cache at `Packages.gitCheckout(commit)`
  (`git/checkouts/<commit>`); its own `path` dependencies are
  `package.git-path`. URLs and revs are held to `Packages.isGitUrl`/`isGitRev`
  (`package.git-url`, `package.git-rev`; the hosts check the same before git
  sees them), and exactly one source to `package.source`. `Packages.resolve` walks the
  tree (one package per namespace: `package.conflict`, `package.cycle`,
  `package.namespace`, …), reported at the requirer's manifest (a package's at
  its package path). Each package is then read (`read`) and validated
  (`validate`) **on its own, in its own namespace**; a project's
  `ReferenceIndex` holds its own namespace whole and each _direct_
  dependency's `Defined.exported` part (`reference.not-exported` when the name
  exists but isn't exported). The root checks `netherforge.lock` against the
  resolution (`Packages.checkLock`: `lock.missing`, `lock.stale`; a hash the
  host didn't give isn't compared). A pinned git package whose hash isn't the
  lock's is `package.hash` and isn't loaded; while one is, `lock()` is null
  and the lock isn't called stale, so no host writes over the pin.
- **The JS protocol.** `packagesNeeded(files, inputs)` answers
  `PackagesNeeded` (folders to read by location, `GitFetchRequest`s to fetch,
  each with a `key`); the host answers both in `PackageInputs` (`folders`,
  `git: { key: { commit } | { error } }`) and asks again until nothing's
  needed. Pins come from the files' `netherforge.lock`: leave it out to
  resolve every git dependency afresh (`netherforge lock --update`, the
  editor's `updatePackages`).
- **Package paths.** A dependency's files are `ns:path`
  (`PackagePaths`): its problems join the root's that way
  (`Packages.inPackage`), `Kinds.classify` answers them with `pkg` set, and
  `KindSpec.locationOf`/`pathOf`/`fileOf` of an id `ns:id` give them. A
  project path never holds `:`.
- **Running.** `ProjectSnapshot.packages` (namespace → `LoadedPackage`:
  resolved + its own snapshot), `everywhere(kind)`/`running(kind)`: the
  project's resources by id and every package's as `ns:id`, which is how the
  plugin names them. A package's resources are compiled from a copy with
  every reference written in full (`ReferenceIndex.qualify`), so they mean
  the same beside the project's, and compiled **under that `ns:id`** (`Resource.compiled`), so a compiled
  resource's `id` is always its key in `running` (the runtime spells instances and finds script files by it: a
  bare id there once made a package's centities run no script); `compiledResourcePacks` holds every resource pack in the
  tree (a package's keyed `ns:id`, built in its namespace, `CompiledResourcePack.key`
  saying where its files are). `lock()` is the lock the packages make.
- **The hash.** `PackageHash.isContent` (manifest, `client/vanilla.json`, resource
  files; nothing hidden, and not `fonts/default.json`, which the editor writes per
  computer from its client import: `isBundled` adds it for `netherforge build`) and
  `listing(digests)` are format's; each host
  supplies per-file SHA-256s and hashes the listing with its own SHA-256
  (the JVM's, Node's, the webview's), so format needs no crypto library.
  `GoldenTest.examplesAreLocked` hashes each example's packages on JVM and JS and
  checks its `netherforge.lock` (`showcase` on `library`).
- **Copying.** `ReferenceIndex.move` (JS `moveRefs`) rewrites a document of
  a package's resource for its copy in the project: the package's things
  become `ns:…`, the resource itself the copy.

## Requirements (declared capabilities)

`netherforge.json`'s `requires` (`ProjectRequires`: `moderation`, `db`, `web`, `bots`, `wasm`,
`http` hosts, `plugins`; docs/format/project.md#requirements) is what a
package's scripts need that a package must ask for. `project/Requirement.kt`
is the model every reader shares: `Requirement` (`Moderation`, `Db`,
`Http(host?)`, `Plugin(name)`) with its `id` (the API spec's spelling:
`moderation`, `http:discord.com`, `plugin:vault`; `parse` reads one back),
its `declaration` (what an error tells the package to add), and
`grantedBy(requires)` (a host against `*.example.com` patterns through
`allows`); `declared(requires)` lists a manifest's, and `combined(snapshot)`
sums the whole tree, each with the namespaces declaring it, the project's
first (`RequirementUse`). The runtime's check and log, the editor's view
(`ProjectOutline.requirements`) and `/nf requires` all read these. Hosts are
`Names.HTTP_HOST` (`project.requires-host`), plugin names
`Names.PLUGIN_NAME` (`project.requires-plugin`), both lists written sorted;
the editor gets both patterns as generated constants. `allow` keeps only
`permissions`, held to the calling package's own manifest the same way
(`Requirements.checkPermission`). `RequirementTest` and the
`invalid/manifest-requires` golden cover it.

## Server-owner settings

`netherforge.json`'s `settings` (docs/format/settings.md) is what whoever runs
the server may change without touching scripts, like a plugin's config. A
setting is a `SettingDef` (`format/settings/SettingDef.kt`): sealed by `type`
(boolean, integer, number, string, choice), each carrying its description,
default and bounds, and owning how it reads a value (`read`, with `typed` for
JSON vs. words typed in a box, so the editor, the command and the values file
agree: JS `readSetting`). Checks (`Settings.kt`) report
`project.setting-*` problems, kept in the manifest's own golden
(`invalid/manifest-settings`). `SettingValues` reads and writes the server's
`settings/<namespace>.json`: it keeps every key (a dropped setting's value
stays) and reports what doesn't fit. Adding a type is a `SettingDef` subclass
plus the editor's field (editors/project/SettingsSection) and `/nf settings`
completion.

## Loot tables

`loot/<id>.json` (`LootTableKind`, `docs/format/loot.md`). Pools are keyed by
name (ids) and rolled in name order; entries and conditions are lists (order
is what a pick walks and conditions are asked in), each a sealed `type`.
Counts and rolls are a `LootRange`: a number, or `{ min, max }`, written as
the number when both ends are equal (its own serializer, special-cased in the
contract generator like `Ingredient`). An item entry is an `ItemDef` without
`count` (`loot.item-count`); a tool condition is an `Ingredient`
(`Rules.ingredient` with `Rules.LOOT_TOOL`'s codes). A game table is a plain
id checked against `RegistryKey.LOOT_TABLE`; a table entry is a `@Ref` to a
project table, and `crossCheck` refuses a cycle among the project's own
(`loot.cycle`; a package can't name the project's). `LootRoller` is the one
place rolling is written: vanilla's rules, everything from the `Random` it's
given, a game table handed back as `LootDrop.Vanilla` with a seed from the
same draws. The server rolls with it, and the editor through the JS
`rollLoot` (every table keyed by full name, each read qualified in its own
namespace with `ReferenceIndex.qualify`, as `running` compiles a package's).
In a package nothing is special: its tables name its own items and tables
bare, are checked in its namespace (its own cycles too), and a project names
only the ones it exports (`exports.loot`; the `packages/loot` case).

## Blocks

`blocks/<id>/block.json` (`BlockKind`, `block/`, `docs/format/block.md`): `model` (a
`@Ref(RefKind.BLOCK_MODEL)`: an entry of a resource pack's `blocks`, `BlockModelDef`, a cube from one
texture or top/bottom/side/north.. with a fallback per face; **optional**, so a template
validates alone: no model draws the plain note block), `hardness` (-1 can't be broken, 0 at
once, default 1.5), `tool` (`BlockTool`: the game's `mineable/<tool>` tag), `requiresTool`,
`drops` (a `@Ref(LOOT_TABLE)`), `sounds` (`place` and `break`, `@Ref(SOUND)` resource pack sounds only:
the carrier's wood sounds play for the rest), `centity` (a `@Ref(CENTITY)`, for a shape that
isn't a cube), `tick` (ticks between `tick` events per placed block: the interval is data so the
runtime only tracks blocks that tick) and the one `script`. An item's `block` (`ItemFile`, not an
`ItemDef` field: it isn't in `look()`) names the block it places. `RefKind` gained `BLOCK`,
`CENTITY` and `BLOCK_MODEL`.

**A block is a note block state** (`BlockCarriers`): the game's `minecraft:note_block` has
`instrument` x `note` x `powered` states (`GameData.block`, so the pool follows the game version:
26.x has more instruments), and the **default instrument's column** (every note, powered or
not) is what players' note blocks live in; every other state is a **carrier**, listed rarest
first (the last instruments, powered before not, the notes). `BlockCarriers.plan(blocks, home,
game)` gives each block, in name order, the next carrier (a block that doesn't run is a null in
its place so the others don't move while it's broken; more blocks than states is `overflow` and
`block.carriers`). The plan is a pure function of the project and the game (so states move as blocks are added; the runtime keeps a
per-chunk **legend**, `BlockOps.legend`, of which state each block was placed as, and records only blocks a centity is
drawn over, blocks with a `data()` table and inert ones): the runtime's
`CustomBlocks` and the resource pack's `PackLayout.build(..., blocks = plan)` compute it the same way,
and `PackLayout` writes `assets/minecraft/blockstates/note_block.json` with **every** state's
model explicit (the block's own for a carrier in use, `minecraft:block/note_block` for the
rest), the cube models (`assets/<ns>/models/block/<pack>/<key>.json`, a `minecraft:block/cube`
child), a model with only the particle texture for a block a centity is drawn over (`_hidden`),
and a shared empty one for such a block with no look. The `blockstates` file is the first
`assets/minecraft/` file besides the default font: a resource pack with blocks changes it, so
`Packs.rebuild` also builds for a project with blocks and no resource pack. The editor gets each look's
faces from `ResourcePackPreview.blocks` (`BlockPreview`: faces by the fallback rule, the particle), so it
never re-encodes the rule. The problem codes are `block.*`, `resource_pack.block-faces` and
`runtime.blocks`; cases: `BlockTest`, `invalid/block-semantics`.

**The game's blocks** (`game_blocks/<id>.json`, `GameBlockKind`, `block/GameBlockFile.kt`, `docs/format/game-block.md`):
`blocks` (required: ids and `#tags`), and `hardness`, `tool`, `requiresTool`, `drops` (a `@Ref(LOOT_TABLE)`), each the
game's own when left out. `GameBlocks.resolve(files, tag)` is the one reading of which file a block is in (first by
id), shared by the checks and the runtime. `GameBlockValidator` (`game_block.blocks`, `.unknown` with game data,
`.twice` within a file, `.hardness`, `.requires-tool`) and the kind's `crossCheck` (`.twice` across files, `.carrier`:
the carriers of the project's blocks, from `BlockCarriers.pool`). A package's run beside the project's
(`running` takes the tree), so a block has one set of rules on a server: the project's file is also held to its packages'
(`KindContext.packageModels`: their rules and their blocks' carriers, reported at the project's file, so the package's
rules hold), and `GameBlockKind.acrossPackages` (run by the root's `validate`) holds the packages to each other and to
the project's blocks, at the root's `netherforge.json` `$.dependencies` (a package is checked on its own, so a clash
between two is the project's to resolve, as `package.conflict` is). The template names `minecraft:<id>` so a new file is for the block it's called after.
`BlockFile.falls` (`block.falls`: not tripwire, not a centity's).

## Terrains

`terrain/<id>.json` (`TerrainKind`, `terrain/`, `noise/`, `docs/format/terrain.md`), a single file whose
companion (`Layout.SingleFile(".json", companion = ".lua")`) is its optional script, `terrain/<id>.lua` (see "Lua
stages" below).
The model is a column recipe: `terrain` (a `base` height plus named `noises`, each a `NoiseDef` times an
`amplitude`; `seaLevel`, `fluid`, `blend`), `layers` from the surface down and `underwater` ones, `stone`, a `floor`,
named `caves` (`cheese` or `spaghetti`), named `ores`, named `decorations` (a block, a custom block or a project
`structure`, a `@Ref(STRUCTURE)`, at a `placement`, `count`, `chance`, optional `noise`/`threshold`, `minY`/`maxY`,
`on`, `rotate`), `climate` noises plus `jitter`, and named `biomes` areas (a biome, `@Ref(BIOME)`: the game's or the
project's, temperature/humidity ranges, optional own layers and an `AreaTerrain`: `base`, `scale` of the file's
noises, own `noises`), `structures.vanilla`. Everything that names a block is a `BlockChoice` (`block` or
`customBlock`, a `@Ref(BLOCK)`; the validator's `choice` says one, `terrain.one-block`). Ores, caves and decorations
take `biomes`, a list of the file's area names (`terrain.area`). Defaults are `...OrDefault` accessors and constants
(`TERRAIN_DEFAULTS`, `TERRAIN_LIMITS`, `WORLD_HEIGHT_OVERWORLD` in the generated constants). `TerrainValidator`
reports `terrain.*` (blocks through `Rules.blockState` with one code, the game's biomes against `RegistryKey.BIOME`,
ranges, names, limits); the kind's `crossCheck` refuses any project block a generator places that has a `centity`
(`terrain.custom-block`: a generator can't spawn the centity), for the project's own blocks only. A new world
generator in `Kinds.all` follows blocks (its states) and `reference.terrain` is the reference code
(`RefKind.TERRAIN`); a decoration's missing structure is `reference.structure`, an area's missing project biome
`reference.biome`.

**Heights are never constants.** `WorldHeight(minY, maxY)` is a world's (maxY exclusive). The validator takes one
(`TerrainValidator.validate(..., height)`, default `WorldHeight.LIMITS`, -2032..2032, what any world can be), the
generator is bound to one (`bind(seed, minY, maxY)`, the server passes the world's own from `WorldInfo`), the preview
takes one per call (the editor passes the heights of the dimension a `netherforge.json` world naming the generator has,
`WORLD_HEIGHT_OVERWORLD` otherwise). A world's dimension type gives the server's `WorldInfo` its heights (nothing in the
generator knows dimensions), and `Projects.validate`'s `worldHeights` holds a generator to the `DimensionTypeFile.worldHeight`
of each `netherforge.json` world naming both (`project.world-height` at the world's `terrain`, the terrain's place
related; only what the world's limits add to `WorldHeight.LIMITS`, compared by code and path).

**Determinism is the contract.** `TerrainCompiler.compile` gives a `CompiledTerrain`: a palette (`TerrainBlock`:
`Vanilla(state)` or `Custom(name)`, 0 is air), everything sorted by name, plain data (equal when the file's
content is). `bind(seed, minY, maxY)` makes a `TerrainGenerator`, immutable after its constructor (the noises are
built there and only read), so one serves every chunk thread. `generate(chunkX, chunkZ)` runs `stages`
(`GenerationStage`s over a `ChunkGeneration`: terrain, carve, floor, ores, decorate) into a `ChunkBuffer` of palette
indexes; a stage reads only the chunk's position and the seed, never other chunks (ores work out the veins
starting in the 3x3 chunks round it and keep their own part, a vein reaching at most 15 blocks; a structure
decoration likewise from the chunks within its reach), which is what lets W5.6's Lua stage fill a range of the same
buffer and W5.4 drive the overworld. Columns' heights and areas go through a `Sampler` (one per chunk or picture,
never shared between threads): **blending** reads areas on a lattice every `BLEND_CELL` (8) blocks, makes each grid
point's area weights with a tent kernel `blend` blocks wide, and interpolates the four grid points' weights round a
column bilinearly, so a height is a pure function of the column (cached in the sampler, never in the generator).
**Jitter** moves the point the climate is read at by two noises (`climate:jitter:x`/`z`). **Decorations** draw five
numbers per try whatever happens (`decorationSite`), so the tries after a refused one don't move, and decide a
structure's site from the column's own numbers (`surfaceAt`, `areaAt`, the top block its layers put there), which
is why a structure can't go on a cave's floor or ceiling (the carved blocks of another chunk aren't known).
**Structures** are linked after compiling: `CompiledTerrain.withStructures(Map<name, StructureTemplate>)` adds each
template's states to the palette once per quarter turn (`StateTurns`: `facing`, `axis`, `rotation`, the side
properties) and keeps `templates`; format never reads NBT itself, the server reads `.nbt` with the game's loader
(`StructureOps.template`) and the editor with its NBT reader, each handing format the same `StructureTemplate`. Only arithmetic that is the same on the
JVM and JS goes in: doubles, Int ops (`TerrainSeeds`: murmur3, FNV-style role seeds, a per-cell unit number),
`kotlin.random.Random(seed)` (XorWow, specified); no `sin`, `cos`, `pow`, `exp`, no `Float` (Kotlin/JS has none).
`NoiseDef.build(seed)` configures a `FastNoiseLite`.

**`FastNoiseLite` is a port** of the reference Java file (MIT, header kept) to doubles: its tables are doubles in
`NoiseTables`, its functions camel-cased, setters as `setSeed(...)`. `FastNoiseLiteTest` holds it to the reference:
`testdata/noise/reference.csv` is the reference's own floats at 230 points (every noise type, fractal, rotation
and warp), which the port must stay within a float's rounding of, and `testdata/noise/golden.txt` (noise at
fixed points and the role seeds, written by `UPDATE_GOLDEN=1`) is asserted **bit for bit on the JVM and in JS**.
`TerrainTest`'s `testdata/terrain/golden.txt` does the same for heights, biomes and a hash and block counts of
two chunks for two seeds, and `TerrainAreasTest`'s `testdata/terrain/areas.txt` for a file with areas' own terrain,
jitter, custom-block ground, filters and decorations (a structure too), alongside its behaviour cases (blending is
chunk-order independent, a structure is whole across chunk borders, each placement puts its block where it says). A change to anything the generator does moves those goldens on purpose: review it.

`TerrainPreview.map/slice` (the JS export `terrainPreviewer(id, text, structuresJson)`, results `TerrainMap`/
`TerrainSlice`/`TerrainFailed` in `editor/EditorResults.kt`, a seed as text, the structures as
`TerrainStructureInput`s) is what the editor draws: the map asks only each column's height and area, and marks
where surface and sea-floor decorations start (`decorationSites`, only while the tries stay under
`MAX_DECORATION_TRIES`), the slice generates whole chunks. `netherforge preview terrain/<id>.json --out map.png`
(apps/cli `preview.ts`) draws the same pictures from a terminal through the shared `packages/terrain-preview`
(colours, pixels, the project's `.nbt` structures); a new picture belongs in that package so both stay one.

**Lua stages (W5.6).** A file's `script` (`TerrainScript`: `budget`, `noises` by name, `blocks`, `customBlocks`, a
`@Ref(BLOCK)` list) hands stages to `terrain/<id>.lua`, a **stage of the file, not another generator**. The script
returns a table of up to four: `height(x, z, height)` (runs inside `Sampler.baseHeight`, after the file's blended
height, `fileHeight`, so layers, sea, caves, ores and decorations all follow it), `density` (W5.11, below), `terrain(chunk)` (a
`GenerationStage` after `terrain`, before `carve`) and `decorate(chunk)` (after `decorate`). Validation: the budget
range, the noises like any (`named`, `noise`), the blocks like any (`Rules.blockState`; `customBlocks` through
`crossCheck`'s list, so a centity-drawn block is refused too), `terrain.script` for limits; the kind's `validate`
sees the files: a `script` with no `.lua` is `terrain.script-missing`, a `.lua` with no `script`
`terrain.script-unused`. Format never parses Lua: a script that doesn't compile or load is found by running it
(`TerrainScripts.check`, which the server calls as it publishes, `terrain.script-failed` at the line), and by the
preview and LuaLS in the editor. `TerrainCompiler.compile(file, scriptPath)` puts the script's blocks at the end of
the palette (so the file's own indexes don't move) and gives `CompiledScript(file, budget, noises)`.

- **Where Lua runs**: `lua/LuaPlatform` is format's one surface over a Lua 5.4 (`open(functions)` → `LuaState`:
  `run`, `call` of a global, `close`; host functions get `LuaArgs` and return a Long (integer), another number
  (float), a String, a Boolean, null or a `LuaChunk` to load, or throw `LuaFailure`). `LuajavaPlatform` (jvmMain:
  luajava's `Lua54`, the plugin's own library and version, every call `pCall`ed, so luajava's sharp edges in the
  plugin-runtime skill apply) and `WasmoonPlatform` (jsMain: wasmoon's Lua 5.4 in WebAssembly through its raw C API
  only, `lua_*` exports, so numbers cross as luajava's do; `loadTerrainLua(wasmUri)` loads the module once,
  asynchronously, before a previewer with a script is made). Whole numbers cross as plain numbers and the glue makes
  them integers (a 64-bit integer is slow to make in JS); the seed crosses as text.
- **The glue** (`src/commonMain/lua/terrain-glue.lua`, which the build's `generateLuaSources` makes the constant
  `TERRAIN_GLUE_LUA` that `terrain/TerrainScriptGlue.kt` runs: one Lua text for both platforms, chunk `=nf/terrain`;
  stylua formats it and `pnpm lint` type-checks it against `src/luals/terrain-host.lua`, the `host_*` functions): takes the
  `host_*` functions into locals and removes them, builds the script's environment (a whitelist: no `nf`, `io`,
  `os`, `debug`, `load`, `collectgarbage`, `print`, `package`, `string.dump`, `math.randomseed`; `__gc` refused,
  the string metatable hidden, `string.rep` capped at 16 MB), the **budget** (one count hook every 1000
  instructions per call, on coroutines too; once over, every later count fails again so `pcall` can't run on), a
  memory limit (`MEMORY_MB`, checked at the hooks and at the end of each GC cycle), `require` of the project's modules
  (`TerrainScripts.modulePaths`: the requiring module's own files, then `modules/<name>/init.lua`,
  `modules/<a>/<b>.lua`), and the API (`terrain`, `Chunk`, `Noise`), then the `nf_load`/`nf_height`/`nf_chunk`
  entry points Kotlin calls. **`math.random` is seeded per call** from `TerrainSeeds.forRole(seed, "script:<stage>")`
  mixed with the column or chunk (`ScriptRunner.heightSeed`/`chunkSeed`), the first time it's asked.
- **States** (`TerrainScripts.kt`): `ScriptRunner` (one per bound generator) keeps a `StatePool` (expect/actual: a
  `ConcurrentLinkedQueue` on the JVM, a list in JS) of `TerrainScriptState`s, one per thread at a time: `generate`
  takes one for the whole chunk (its heights and stages run in it), a lone `surfaceAt` takes one for the call.
  `TerrainGenerator.close()` closes the idle ones and the rest as they're given back (the generator keeps working,
  a state per call). A state's body runs once, when it's made. Failures go to `TerrainScripts.failed` from
  whichever thread, every time they happen (each is deterministic): `TerrainScriptFailure(file, stage, message)`,
  `location` its file and line. A failed `height` is the file's height there; a failed chunk stage's changes are put
  back (a copy of the buffer before it). The fill API writes `ChunkBuffer.fill` runs, clipped to the chunk and the
  world; block ids resolve to palette indexes once per state (`host_resolve`: a label, or the game's state parsed
  and canonicalised).
- **The API's spec** is `packages/api/src/terrain.ts` (not `nf`'s: no bindings are generated for it), which
  `pnpm generate` turns into `generated/luals/terrain.lua` (its own stub file, classes on local tables so nothing is
  a global elsewhere), `docs/reference/terrain-scripts.md` and `generated/terrain.json`, which
  `TerrainScriptApiTest` holds the glue to (every function there, nothing else). A new function: spec, `pnpm
generate`, the glue (and a `host_*` if Kotlin must answer), a case in `TerrainScriptTest`.
- **Tests**: `TerrainScriptTest` runs every case on both platforms (`withLua`: `LuaTest` is the JS `Promise`, since
  wasmoon loads asynchronously): `testdata/terrain/script/` (a file, its script and a module) gives
  `testdata/terrain/script.txt`, the same golden on the JVM and in JS (heights, chunk hashes and block counts for two
  seeds), plus the budget, a failing height, the sandbox, messages at their line, memory, the check, the palette
  order and module paths.
  **3D terrain (W5.11).** `terrain.density` (`Density`: named 3D `noises`, each a `DensityNoise` with `amplitude` and
  `squash`, and `islands`: `y`, `thickness`, `noise`, `threshold`, `biomes`), an area's `terrain.density`
  (`AreaDensity`: `scale` of the file's 3D noises, own `noises`; only in a file with one, `terrain.density`), compiled
  into `CompiledDensity`/`CompiledIslands` and the area's `CompiledAreaTerrain.densityScale`/`densityNoises` (defaults
  equal a heightmap area's, so files without a density compile as before). `terrain/TerrainDensity.kt` is all of it:
- **The field** (`DensityField`, built in the generator's constructor, read-only): a block's density, in blocks, is
  `base + 0.5 - y + ground(x, y, z)`, or the islands' where higher, plus the script's change; solid where it's above 0.
  `base` is the column's own `Sampler.baseHeight` (the file's blended 2D height, then the script's `height` stage),
  exact per column; `ground` (the areas' 3D noises, each clamped to -1..1, weighed by the
  same blend weights heights use, `blendWeights`, read at the grid column) and the islands (threshold raised towards
  1 by `1 - weight` of the islands' areas, so they fade at a border) are read on a world-aligned grid every
  `CELL_XZ` (4) across and `CELL_Y` (8) up, and interpolated trilinearly. With no 3D noise the ground is the
  heightmap's exactly (`TerrainDensityTest` holds a file with `"density": {}` byte-equal to the same file without).
- **Bounds instead of work.** The ground's offset is at most `reach` (the largest sum of an area's amplitudes), so a
  block more than that below its base is solid and above it air, and islands can't reach past the grid levels round
  their band (`islandsTop`): `DensityColumn.solid(y)` only interpolates in between, and `highest()`/`solidBelow(y)`
  let a column's scan start and stop there. A script with a `density` stage can change any point, so then nothing is
  skipped (`DensityField.scripted`, from `ScriptRunner.stages`, the stages one state reports).
- **One answer.** Grid columns are `CornerColumn`s cached in the `Sampler` (values filled per level on first read:
  NaN is "not yet"); a block's solidity is `DensityColumn.solid`, which the chunk (`generate` marks a `solid` mask
  before the stages, `ChunkGeneration.solid`, and `surface` is each column's topmost) and the column (`surfaceAt`, the
  topmost solid block or `minY - 1`; `surfacesAt`, every top surface) both ask, so they can't disagree.
- **Downstream**: `densityTerrain` tops every surface with the layers (`underwater` below sea level) and fills every
  non-solid block at or below `seaLevel` with the fluid; `densityCarve` carves only solid blocks with at least the
  cave's `depth` of solid ones above (the heightmap rule, generalised); `decorationSite` puts `surface`/`underwater`
  decorations on any of `surfacesAt`'s tops of the right kind, picked by the try's `pick` number (one top without a
  density, so heightmap goldens didn't move); spawn, `getBaseHeight` and the preview's map read `surfaceAt`, the
  topmost.
- **The script's `density(x, y, z, value)` stage** runs per grid point (`nf_density`, `math.random` seeded by
  `script:density` and the point), budgeted per call; its change from the file's value is what's interpolated, and a
  failure there is no change. A `density` stage in a file without a density is a `load` failure as the state loads.
  `terrain.height` stays the base height.
- **Cost**: `TerrainDensityTimingTest` (jvmTest, only with `NETHERFORGE_BENCH=1`) prints chunk and map timings of `testdata/terrain/density.json`
  against the same file's heights (about 1.6x a chunk on the JVM, about 3x the map). Goldens:
  `testdata/terrain/density.txt` (JVM and JS, bit for bit).

**Volumes, climate values, area and biome stages, plans, loot.** An area with `y`, `depth` or `surface`
(`BlockRange`s) is a **volume** (`BiomeArea.isVolume`, `CompiledArea.volume`: `CompiledVolume`): never a column's area
(`CompiledTerrain.columnAreas`/`volumeAreas`; `fileAreaAt` picks among column areas only), it's a place's.
`pointAreaAt(x, y, z, sampler)` reads a place at its 4x4x4 cell's corner (the game keeps biomes per cell, and Paper
asks the corner): the smallest-span volume whose ranges hold it and whose climate box holds the column's climate
exactly (`climateCost == 0`), else the column's area, then the script's `biome` stage; `biomeAt(x, y, z)` is what the
server's `BiomeProvider` answers (a thread-local `Sampler` per generator in `PaperWorldGenerators`). A chunk computes
its cells once (`ChunkGeneration.cells`, only when `pointAreas`), and caves, ores and decorations match their
`biomes` against the column's area **or** the place's (`allowedAt`), so files without volumes behave and hash exactly
as before. `climate.noises` (`CompiledClimateNoise`, seeds `climate:noise:<name>`) add axes: `CompiledArea.climate`
is min, max per axis; cost and span include them. `terrain.volume` covers what a volume can't have. Script stages
`area(x, z, area)` (a column area's name; `Sampler.areaAt` caches it) and `biome(x, y, z, area)` (any area) run in
Kotlin-called entry points `nf_area`/`nf_biome`; **a host function never calls back into the state that called it**:
host functions use the state's own `Sampler` with no script state, so a script stage they need takes another state
from the pool (luajava's and wasmoon's stacks aren't re-entrant through `call`). The glue's `running` stage name
guards cycles (no `terrain.height`/`area` from `area`; no place reads from `height`, `density`, `biome`).
`terrain.plan(name, size, make)` lives entirely in the glue: a per-state cache (256 cells), and `math.random` inside
`make` is a splitmix64 of the cell (`current_rng`), so a cached or fresh plan never moves the stage's own numbers
(`testdata/terrain/plans.txt` holds JVM and JS to the same bits). Loot: `CompiledTerrain.loot` (sorted names from
decorations' `loot` and `script.loot`), `ChunkBuffer.loot` (buffer index → table index, `lootPlaces`), marked by
block decorations, by a structure's entries in `StructureTemplate.withEntity` (the readers say which states have a
block entity), and by `chunk:set_loot`; a failed script stage puts the marks back too. `TerrainKind.datapackAll`
adds `GeneratedLoot`'s empty `netherforge:generated_container` table when any terrain has loot: the server gives
containers that table plus the project's table name in their persistent data, and rolls the project's when the
game unpacks it (`PaperGeneratedLoot`, `PlatformEvents.generatedLoot`).

**The docs pages are checked against the format** (`docs/tests/format-docs.test.ts`, run by `pnpm test`): every
`json` block in `docs/format/*.md` is a whole file and must validate as its kind (`canonicalize`, and `loadProject`
for the kinds a project holds, ignoring only what a lone example lacks: its neighbours); a snippet is marked
`json partial`, a whole file with no `$schema` is `json kind=<id>`; and every key in a kind's JSON Schema must be
named on its page (in backticks or as an example's key; a kind spread over several pages is listed in the test's
`PAGES`). Adding a key to a model fails that test until its page says what it does. The agents' copy of the docs
(`.netherforge/docs`) is a build-time glob of those same files (`@netherforge/project-support`'s `bundled.ts`, which the
editor and `netherforge setup` both write from), and a test reads the folders to prove it.

`netherforge.json`'s `worlds.<name>` has a `terrain` (a `@Ref(TERRAIN)`) and a `seed`. The manifest's own
references were never walked before: `Projects.validate` now finds them (`ReferenceIndex.usesOf` with
`ManifestKind`) and checks them like any file's, so `reference.terrain` lands on `netherforge.json` and find
usages and renames follow a generator named there.

## Biomes

`biomes/<id>.json` (`BiomeKind`, `biome/`, `docs/format/biome.md`), a single file, no script: `climate`
(temperature, downfall, precipitation, `temperatureModifier`), `colors` (`#rrggbb` each, `grassModifier`),
`particle` (one that takes no options: `GameData.particle`'s kind must be `NONE`), `sounds` (`ambient`, `mood`,
`additions`, `music`), `spawns` (keyed by `SpawnCategory`, lists of `BiomeSpawn`: `entity`, `weight`, `group` a
`SpawnRange`), `spawnCosts` (by entity type) and `features` (keyed by `GenerationStep`, ordered lists of placed
feature ids). `...OrDefault` accessors and `BIOME_DEFAULTS` (generated) are what absence means. `BiomeValidator`
reports `biome.*`; ids go through one helper that checks the shape always and, with game data, the registry
(`ENTITY_TYPE`, `SOUND_EVENT`, `PARTICLE_TYPE`, `PLACED_FEATURE`). The kind's `crossCheck` is `biome.feature-order`:
the game sorts every step's features across all of a world's biomes into one order and refuses a cycle, so two
project biomes listing two features in opposite orders are refused (the game's own biomes aren't data we have).

**References that also name the game's.** `RefKind.BIOME` is `game = true`: a value in the game's namespace
(`minecraft:plains`) or a `#tag` is the game's (`RefKind.isGame`), which `ReferenceIndex.check` skips (the kind's
validator checks it against `RegistryKey.BIOME`), and find usages and renames never match; anything else is the
project's or a package's, so **a plain id is always the project's** (`reference.biome`'s message then says how the
game's are written). Terrain areas, centity `spawning.biomes` and `structures/<id>.json`'s `biomes` are all
`@Ref(BIOME)`. The structure's generation file is a binary kind's companion: `KindSpec.companion(value)` gives its
JSON so `Projects` walks and checks its references at `companionPathOf`, as for any document.
`BiomeKind.keyOf(text, home)` is the one way to say what the server calls one (`minecraft:plains`,
`#minecraft:is_forest`, a project's `showcase:ruby_grove`): `StructureJson`, the runtime's generators and the spawner
use it. A structure naming a project biome that isn't running waits, out of the datapack (the game refuses a pack
whose structure names a missing biome).

**The game's format per version.** `BiomeJson.of(file, ctx)` writes `worldgen/biome/<id>.json`: sky, fog and water
fog colours, particles, sounds and music as environment `attributes` (every supported version), the other colours in
`effects`, every step present (11 lists), no carvers, and the spawns either as `spawners` + `spawn_costs` (1.21.11 to
26.2, `minCount`/`maxCount`) or as the `minecraft:gameplay/natural_mob_spawns` attribute (26.3's data pack format
121 and later, `count` an int or a `minecraft:uniform`), chosen by `DatapackContext.formatAtLeast` from the server's
data pack format (the game ignores the other way's fields, so the wrong one spawns nothing). This was read off each
supported server jar's own `data/minecraft/worldgen/biome/*.json`, not guessed: do the same when a version moves it.
`testdata/datapack/biomes.json` holds both shapes. Cases: `BiomeTest`, `invalid/biome-semantics`.

## Dimension types

`dimension_types/<id>.json` (`DimensionTypeKind`, `dimensiontype/`, `docs/format/dimension-type.md`), a single file, no script: `minY`,
`height`, `logicalHeight` (the game's rules: sections of 16, -2032..2032, logical no taller; `dimension-type.height`,
`dimension-type.logical-height`), light and sky (`skyLight`, `ceiling`, `ambientLight`, `fixedTime`, `sky` = the game's
`skybox`, `colors` sky/fog/clouds, `cloudHeight`), rules (`bedWorks`, `respawnAnchorWorks`, `piglinSafe`, `raids`,
`ultrawarm`, `monsterSpawnLight`, `monsterSpawnBlockLight`, `infiniburn`, `coordinateScale`). Absent is the overworld's
(`...OrDefault`, `DIMENSION_TYPE_DEFAULTS` generated). `RefKind.DIMENSION_TYPE` (`reference.dimension-type`); `WorldConfig.dimensionType` is
one, and `DimensionTypeKind.keyOf` is the server's key (always `ns:id`). It's start-up datapack data: `datapack` writes
`data/<ns>/dimension_type/<id>.json` (`DimensionTypeJson`, gated by `DatapackContext.formatAtLeast`: see minecraft-versions),
and `datapackAll` writes `data/minecraft/dimension_type/overworld.json` (the same JSON) when `DatapackContext.mainWorld`
(what `StartupDatapack.build` is given: the adapter's `level-name`) has a running dimension in the manifest: the main world
is made by the server before any plugin, so replacing the overworld type is the only way, and it changes every
overworld-typed world that names none. The game's overworld ids written there (`#minecraft:in_overworld` timelines, the
`minecraft:overworld` clock) are the type's day, not data we keep. Cases: `DimensionTypeTest`, `invalid/dimension-type-semantics`,
`testdata/datapack/dimensions.json` (formats 94, 101, 121).

## Cutscenes

`cutscenes/<id>.json` (`CutsceneKind`, `cutscene/`, `docs/format/cutscene.md`), a single file
like a recipe, no script. The camera is keyed the way a centity's animation is:
`camera.position` is a list of `Keyframe`s (the centity's own type: `time` in seconds,
`value` a `Vec3`, `easing` for the segment leaving it) and `camera.rotation` of
`RotationKey`s (`time`, `yaw`, `pitch`, `easing`), so the timeline and `keys.ts`
treat them like any keyframes; `cues` are `{ time, event?, text?, duration? }`
(`text` is MiniMessage, so glyphs are references). `canonical()` sorts each by time.
`CameraPath` (`CompiledCutscene.path`) is the one place a pose is worked out: linear or
eased between the two keys around a time, a yaw the short way round
(`math/Angles.shortestDelta`, which `CompiledAnimation` shares), held outside the
keys; the server samples it every tick and the editor's JS export `cutsceneDirector`
(`CutsceneDirector.shot/trail`, results in `editor/EditorResults.kt`, registered in
`Contract.kt`) samples it for the preview. `length` defaults to the last key or cue;
the validator (`cutscene.*` codes: length, empty tracks, key times and duplicates,
the limits, position and pitch ranges, cues) never reads anything but the file, so
there's no `crossCheck`. Cutscenes are named by scripts only (`names: 'cutscene'`),
not by other files, so there's no `RefKind`. `CUTSCENE_LIMITS` is the editor's copy
of the numbers. Cases: `CutsceneTest`, `invalid/cutscene-semantics`.

## Advancements and the start-up datapack

`advancements/<id>.json` (`AdvancementKind`, `advancement/`, `docs/format/advancement.md`):
`parent` (a `@Ref(RefKind.ADVANCEMENT)`; `crossCheck` refuses a cycle,
`advancement.cycle`), `display` (icon = `kind` or a project `item`, never both;
title and description are `@MiniMessage`), `criteria` (a map; no `trigger`
means only a script's grant meets it, the game's `minecraft:impossible`),
`requirements` and `experience`. The game learns advancements only while it
loads, so a kind that sets `KindSpec.datapack` puts files into the
**start-up datapack**: `StartupDatapack.build(snapshot, format, text)` is
pure (project files only, never game data, no clock), sorted and so
byte-stable, which is how the runtime tells a change needs a restart.
`AdvancementJson` writes the game's format (text through the `TextJson`
callback, glyph and `item_model` ids from the resource packs). A package's resources
go in their own namespace; a resource with errors is left out. A new kind
that the game reads only at load sets `datapack` the same way (biomes do). Where the game's JSON differs between
supported versions, a kind asks `DatapackContext.format`/`formatAtLeast` (the server's data pack format, which
`build` is given) rather than the project's target. A kind whose
files are one per kind or only for some resources sets `datapackAll`
(`DatapackCollection`: every running resource by key): dialogs use it for
`pauseMenu`/`quickActions` (`DialogJson`, the registry dialogs and the two tags;
see the menus-and-dialogs skill). `StartupDatapack.kinds` is every kind with
either. A kind that writes registry entries also says which (`KindSpec.datapackEntries(id, value)`: registry folder →
paths, biomes their own, a generating structure its structure, set and pools), so a passed-through datapack can't
write the same entry (`datapack.conflict`) and its files may name them; a new kind writing into the pack (W5.12's
dimension types) adds its entries there too. `DatapackEntry.Copy` copies a project file in as it is (a structure's
`.nbt`, a datapack's JSON).

## Datapacks passed through

`datapacks/<id>/` (`DatapackKind`, `datapack/`, `docs/format/datapack.md`): the game's own worldgen JSON, for experts,
laid out as any datapack (`pack.mcmeta`, `data/<ns>/worldgen/<registry>/<path>.json`, tags under
`data/<ns>/tags/worldgen/<registry>/`, overlay folders). The main file is `pack.mcmeta` (`PackMeta`, the game's
spelling: `pack.min_format`/`max_format` as a number or `[major, minor]`, `overlays.entries`), the one document of the
kind format writes. The other files are **read but not modelled**: `KindSpec.readsFile` makes the loader read a folder
kind's other files as text (`ResourceContext.text`/`json`, parsed once, kept in the `ProjectCache` with the resource so
an unchanged datapack parses nothing again); every other kind reads only its main file.

- **Version gating is the game's own**: `PackFormat` compares formats as the game does (a whole number as the upper end
  is every minor of it), `CompiledDatapack.filesFor(format)` applies the overlays in order for one server's format, and
  the start-up datapack gets exactly those files (`DatapackEntry.Copy`, no overlays of its own); a server outside the
  pack's range gets none of it, and the target version's format (`GameData.dataPackFormat`, from `version.json`;
  `GameDataBundle.SCHEMA` 2) outside it is `datapack.version`. Each version's own shape of a file goes in an overlay;
  `data/` holds what every version reads alike (the example's boulders: the placed feature in `data/`, 1.21.11's,
  26.1-26.2's and 26.3's configured feature each in an overlay; 26.3 renamed `configured_feature`/`configured_carver`
  to `feature`/`carver` and reshaped noise settings and density functions).
- **What's checked, and nothing more** (`DatapackValidator`): `validate` (alone, with the game) places each file
  (`DatapackLayout.place`: worldgen entries and tags only, `datapack.file`), parses it, and for the files the target
  reads checks the registry exists (`datapack.registry`, a warning), a `minecraft` file replaces something the game has
  (`datapack.override`), and the game's ids it names exist; `crossCheck` (against the project) checks namespaces (the
  project's own and `minecraft`; a package's only its own, `datapack.namespace`), collisions with `datapackEntries` and
  between datapacks (`datapack.conflict`), and references in the home namespace against every datapack's entries and
  NetherForge's own, and other namespaces against the packages. Where references are is `WorldgenReferences.RULES`
  (JSON paths per registry, a rule's targets listing every name its registry has had); the game's codecs are not
  re-implemented: the server checks the rest. `PaperDatapackCheckTest` (integration contract, Paper only) runs the
  validator and the rules over each supported server's own worldgen files with its registries, so a rule wrong for a
  version fails there: add a rule only with that test green on every version.
- **Naming what they define**: `RefScope.DATAPACK` (`RefKind.PLACED_FEATURE`, a biome's `features`) names entries a
  datapack defines; `RefKind.BIOME` keeps naming project biomes and also a datapack's `worldgen/biome` entries
  (`RefKind.registry`). `Projects` adds the datapacks' home-namespace entries to `ReferenceIndex` names, so the usual
  reference checks, packages' exports included, apply; references inside a datapack's files aren't walked (no find
  usages or rename there). `ProjectOutline.registryNames` gives the editor those names per registry folder.
- **The start-up datapack** copies a running datapack's files for the server's format (`DatapackContext.passedThrough`
  lists what's in, `passThrough = false` leaves every datapack out), and a biome naming a datapack's feature that
  isn't in the pack waits (`BiomeJson.writes`), as a structure naming a missing biome does (`DatapackContext.biomes`).
  `StartupDatapack.sourceOf` maps an entry the server complained about back to the file that wrote it.
- Cases: `DatapackTest` (formats, overlays per version, the game's data, the cache), `invalid/datapack-semantics`,
  `packages/datapacks`, the `datapack/showcase.json` golden.

## Migrations: `migrations/NNN_name.sql`

`MigrationKind` (`migration/MigrationFile.kt`) is a `FilesKind` with
`Contents.SQL`: one file per resource, the id `001_init`, never read by
format (the runtime reads the SQL and applies it to the package's database,
see plugin-persistence "Package databases"). Format checks what it can know from
the names: `validate` reports a file not named `NNN_name` (`migration.name`);
`crossCheck` (over `ctx.models(MigrationKind)`, so per package) reports a
number used twice on the later file (`migration.duplicate`) and a number
that doesn't follow the one before, the first included (`migration.gap`).
`migration.failed` is the runtime's (a migration the database refused).
`exportable` is false: a package's schema isn't something others import
(`exports` can't list `migrations`), and `sampleId` is `001_sample` because
`KindsTest` makes every kind with one. Spec: `docs/format/migrations.md`.

## Screens, screen fonts and videos

`screens/<id>/screen.json` (`ScreenKind`, `screen/ScreenFile.kt`, docs/format/screen.md): size in maps (1 to
`MapScreen.MAX_TILES`), background, `place` (world, the top left map's block, `ScreenFacing`: walls only; right is
`(normalZ, -normalX)`), viewers, range, glowing, click range, an autoplay `video` (`@Ref(RefKind.VIDEO)`) and the
one `script` (`this` a `Screen`). `crossCheck` warns two placed screens sharing a block (`screen.place`).
`screen_fonts/<id>/font.json` (`ScreenFontKind`): `pixels` (glyphs as rows of `#`/`.`, the template's digits),
`bitmap` (a PNG grid, `chars`; `screen_font.grid` from the picture's size, `crossCheck`) or `truetype`; `default`
is the game's font, so no project font may be called that. `videos/<id>/video.json` (`VideoKind`): the source and
how it's converted; `video.nfv`, `audio.ogg` and `audio/<n>.ogg` are files of the folder format never reads,
only checks are there (`video.not-converted` when the source is there and nothing converted; nothing for a video
with neither, which is being set up). `MapPalette` and `Dither` are the one colour matching (redmean, 15-bit cache;
Floyd–Steinberg searches exactly), shared by the runtime and previews; the editor's Rust converter repeats them
(`video/palette.rs`), and both pin the same distances in tests. `VideoSound.of` gives `PackLayout.build` each
video's sounds. Items gained `map` (a stack's own field, `ItemFile.STACK_FIELDS`).

## Binary resources: maps and structures

`maps/<id>/` (a Minecraft world folder, `level.dat` at its top) and
`structures/<id>.nbt` are Minecraft's own binary files: `MapKind`
and `StructureKind` are `FilesKind`s (`Contents.BINARY`), like modules
(`ModuleKind`, `Contents.LUA`): no `DocumentKind`, no schema, no canonical
form. A `FilesKind` resource is built from its file list alone (`of`), and
format **never reads** these files (the editor and the CLI hand format `null`
for them, and must not read a binary kind's folder as text: a world has
`.json` stats files). The loader checks the id, that a world folder has its
`level.dat` (its main file), and reports anything else in those folders as
`project.stray-file`, as for every kind. Only the server reads the contents.
They're `snapshot.models(MapKind)`/`(StructureKind)`.

**Builds** (`builds/<id>/`, `BuildKind`, `world/BuildFile.kt`): a structure too big for one file, a `FilesKind`
(`Contents.BINARY`, `Layout.Folder(Builds.MAIN)`: its first part, `0_0_0.nbt`, makes a folder one) whose other files
are its parts, `<x>_<y>_<z>.nbt` (`Builds.partOf`/`fileOf`), each `Builds.PART_SIDE` (48) a side but the last along
each axis. Format still never reads them: `BuildFile.grid` is one past the furthest part's name along each axis, and
`validate` checks the names alone (`build.part-name`, a warning for a file that isn't a part; `build.part-missing`, a
gap in the grid; `build.size`, past `Builds.MAX_PARTS` along an axis); `crossCheck` refuses a structure's id
(`build.id-taken`: scripts name both alike). `BuildGrid.parts` is the order they're placed in (bottom up, x fastest),
which `@netherforge/structures/parts` repeats in TS from the generated `BUILD_LIMITS`. `EmptyBlocks.IDS` (generated
`EMPTY_BLOCKS`) is the one list of blocks that mean "nothing here": a terrain's decorations leave them out and the
structure pictures don't draw them. Cases: `BuildTest`.

**WebAssembly modules** (`wasm/<id>/`, `WasmModuleKind`, `wasm/WasmModuleFile.kt`): a `FilesKind`
(`Contents.BINARY`, `Layout.Folder("module.wasm")`) whose other files are its `data`, read by the module from its
root at run time. Format still never reads a binary file: what checking needs of the module comes from the host
as **facts** (`WasmInfo`: imports, exports with their types, memory, or why it isn't a module), through
`ProjectSource.wasm(path)` / `readsWasm`, the way pixels come through `image`. The facts are made by format's own
`WasmReader.read(bytes)` (common code: the header, then each section by its size, reading only types, imports,
functions, memory, exports and start; vectors' counts held to their sections' bytes so a forged count can't make a
huge list), which the plugin's `DiskProjectSource` calls and the CLI calls through the JS export `readWasm` before
`loadProject(..., wasmJson)`; the editor passes none (its screen calls `readWasm` itself), so its problems come from
the dev server. `crossCheck` (facts are `KindContext`'s) reports `wasm.invalid`, `wasm.import` (anything but
`WasmImports.FUNCTIONS` from `wasi_snapshot_preview1`), `wasm.threads` (a memory import, `wasi.thread-spawn`, shared
memory), `wasm.memory` (memory64) and `wasm.exports` (a warning: no function). `WasmImports` is the one list the
runtime's compile holds imports to as well. Goldens hold no facts, so these are `WasmTest`'s (ProblemCoverageTest's
`testedElsewhere`). It's exportable; it reloads before modules (`Kinds.all`), so a script restarted by it finds it
compiled.

**Structures that generate (W5.1).** `structures/<id>.json` beside the `.nbt`
is the one document in a binary folder: `Layout.SingleFile(".nbt", companion =
".json")` makes `Kinds.classify` answer it as `PathRole.FILE` of the structure
with `document = "structure_generation"` (`KindSpec.companionDocument`,
`StructureGenerationKind`, a `DocumentKind` in `Kinds.documents` with a schema
and `types.ts`), `Projects.read` reads it only beside its template
(`project.missing-file` otherwise; a parse failure is reported at it and the
structure still reads, with no `generation`), `FilesKind.ofCompanion` builds
`StructureFile(id, generation)`, and `validate`/`crossCheck` report at the
`.json`'s path through `ResourceContext.report`. The `KINDS` table has
`companion` for the editor (`companionOf`, `isProjectJson` lets format's own
documents through a binary folder). `StructureKind.datapackAll` writes the
datapack (`StructureJson`): a `minecraft:jigsaw` structure over the pool
`<ns>:<id>/start` (the template alone), a one-structure `random_spread`
structure set with a salt from the name (FNV-1a, pinned by a test), a pool
per `pools` entry, and a copy of the template of every generating structure and
of each its pools pick (`DatapackEntry.Copy`). Field names follow the game's
`terrain/*` JSON; the game itself refuses a piece without `processors` and a
pool without `fallback`, which only a real server told us: when a field is
added, load the datapack on a cached Paper jar (`docs/format/worlds.md`
has the fields). No feature is gated: every field exists on 1.21.11 and 26.x. The
manifest's `managedWorlds` (names held to `Names.WORLD_NAME`, written sorted)
says which existing server worlds scripts may unload. Its `worlds` map
(`WorldConfig`, by world name, held to `Names.WORLD_NAME`) holds per-world
settings: `spawnLimits` and `spawnIntervals`, each a map keyed by
`SpawnCategory` (the enum's `id`s are the Lua API's `category` choices;
`MISC` has no cap and isn't one), values 0 or more (`project.world-spawn`).
A world that says nothing is dropped when written.

## Facts only the live server has

`GameData.itemDurability` (an item kind's default `max_damage`) is answered
by `PaperGameData` and null from cached bundles, so `Rules.item` refuses a
stackable durable item on the server and when a script builds one, and in
the editor only when `damage` says so. A check like that skips quietly when
the answer is null.

## Natural spawning (`centity.json`'s `spawning`)

`SpawningDef` (`centity/CentityFile.kt`, `docs/format/centity.md#spawning`):
`worlds`, `biomes`, `blocks` (ids, or `#tag`s for the last two), `light`,
`height` and `group` (a `SpawnRange`, `{ min?, max? }`), `weight`, `cap`,
`despawnDistance`, `keepOnInteract` (a boolean, default false: a click keeps a
natural one; nothing to validate beyond its type). Every field is optional and the `...OrDefault` accessors and
`DEFAULT_*` constants are what absence means (the editor reads them from the
generated `SPAWNING_DEFAULTS`); an empty list is as absent. `CentityKind.canonical`
sorts and de-duplicates the three lists. `CentityValidator.validateSpawning`
reports `centity.spawning-world` (a name `Names.isWorldName` refuses),
`-biome` (the game's ids and tags; a project biome is a reference, see "Biomes") and `-block` (shape; with game data, a registry id, or a tag, the game
doesn't have: `RegistryKey.BIOME` is `minecraft:worldgen/biome`), `-range`
(min above max; light outside 0 to 15), `-number` (weight, cap, group,
despawnDistance below 1) and the warning `-despawn` (a distance within the
spawner's 48-block reach). The compiled centity carries the block as is
(`CompiledCentity.spawning`); the runtime's `NaturalSpawner` resolves it (see
entities-and-physics, "Natural spawning"). Cases: `CentitySpawningTest`, `invalid/centity-spawning`.

## Adding a field to an existing kind

1. Add it to the `@Serializable` model with a default (usually `null`), a KDoc
   line, and validation in the kind's validator if it can be wrong. Text a
   player sees gets `@MiniMessage`, and a field naming another resource is a
   `ResourceRef` with `@Ref(RefKind.X)`: then its references are checked,
   found as usages and followed by renames wherever it sits (`RefWalker`
   walks every document).
2. Nothing to do for ordering: `CanonicalJson.write` puts every map in key
   order (enum keys in declaration order, whole-number keys numerically),
   however deep it sits. Only a list whose order isn't meaningful (keyframes)
   is sorted in the kind's `canonical()`.
3. Update `docs/format/<kind>.md`.
4. Use it in an example under `examples/` if it's user-facing, then run
   `UPDATE_GOLDEN=1 pnpm test` so the example is rewritten canonically, and
   review the diff.
5. Add an invalid case under `packages/format/testdata/invalid/` for each new error.
6. If the plugin or editor reads it, update them in the same change.

## The registry and the snapshot

- **Layout.** `Layout.Folder(main)` (`<folder>/<id>/…`, and a folder without
  `main` is `project.missing-file`; `main = null` for a module, which any file
  makes) or `Layout.SingleFile(extension)` (`recipes/<id>.json`, nothing beside
  it). `pathOf(id)` is the main file, `locationOf(id)` what rename and delete
  act on, `fileOf(id, file)` a file in the folder.
- **`Kinds.classify(path)`** answers `{ kind, id, role, document, rest }`
  (`role`: `main`, `file`, `folder`, or `project` for `netherforge.json` and
  `fonts/default.json`) from the path alone. It doesn't check ids; hidden
  names and strays are null. The loader reports strays (`project.stray-file`)
  and bad ids (`project.id`) the same way for every kind.
- **Loading** (`Projects.load`): classify every file into kind → id → its
  files; read each resource (a `DocumentResourceKind` parses its main file, a
  `FilesKind` builds from the file list) and validate it **on its own**
  through `KindSpec.validate(value, ctx: ResourceContext)`: the id, the
  resource's files, `GameData?`, the target version, its `sink` (the main
  file) and `problem()` for its other files, and nothing else of the
  project. Then, with everything read, check each **against the rest**
  through `KindSpec.crossCheck(value, ctx: KindContext)`, which adds the
  other resources (`ctx.models(kind)`), the default font, images and the
  `references` (`ReferenceIndex`). A rule that reads anything beyond the
  resource goes in `crossCheck` (the types keep it out of `validate`),
  because `validate`'s answer is cached: a `ProjectCache` passed to
  `load` keeps each file's read-and-validated result (resources, the
  manifest, the default font) while its text, its folder's files, the game
  data and the target version are equal to last time, so a load after an
  edit validates only that file and then runs the cross pass, which is
  lookups (and compiles a kept resource only once). `cache.validated` lists
  what the last load validated afresh; each dependency package is read
  through its own `cache.scope(namespace)`, dropped once the project no
  longer depends on it. The
  editor's validation worker holds one through the JS `ProjectValidator`
  (see editor-ui); the plugin and the CLI load without one. The loader itself checks,
  for every document kind, every reference the walker finds in it
  (`reference.syntax`, `.namespace`, `.item`, `.dialog`, `.resource-pack`, `.resource-pack-key`,
  the last pointing at the resource pack too through `related`), and for every kind
  with a script, the `script.file-name` warnings. A kind's `crossCheck`
  checks only what a reference means beyond existing (`ItemKind.checkStack`:
  a stack's kind against its project item's).
  A resource with no errors is compiled (`KindSpec.compile`, which sees the
  `ResourceContext` alone, so a cached resource compiles once; a kind without a
  compiler compiles to itself).
- **The snapshot** is `resources: Map<kindId, Map<id, Loaded<T, C>>>`.
  `snapshot[kind]` is typed; `models(kind)` is every resource that read (errors
  or not: the editor shows them), `compiled(kind)` only those the server runs.
  Resource packs are also built together (`ResourcePackKind.build`): `compiledResourcePacks`, by resource pack
  id. `references` is the project's `ReferenceIndex` (every `RefUse` with its
  file, JSON path and resolved target, and what each namespace defines),
  `namespace` its home.
- **The order of `Kinds.all` is the reload order** (the hot-reload skill):
  resource packs, particle effects, cutscenes, screen fonts, videos, modules, structures, maps, items, recipes,
  loot tables, blocks, biomes, terrains, advancements, menus, dialogs, screens, centities.

## Adding a resource kind

1. The model in `packages/format/src/commonMain/.../<kind>/` and its validator
   (a plain `validate(file, sink, …)` that tests can call on its own).
2. One `KindSpec` object in `.../<kind>/<Kind>Kind.kt`, beside the model: a
   `DocumentResourceKind` for JSON (`canonical()`, `validate` calling the
   validator, `crossCheck` for anything it checks against other resources, `compile`, `template`, and `script`/`scriptOf` if it runs Lua)
   or a `FilesKind`, and its place in `Kinds.all` (which is also its reload
   order). Its references need nothing but `@Ref` on their fields. If other
   files can name it, it gets a `RefKind` (`resourceKind = "<its id>"`) and a
   `reference.<kind>` code in `ProblemCodes` (`reference.loot-table` for
   `loot_table`); what it defines and the broken-reference message follow
   from those (`Projects.defined` and `ProblemCodes.missing` read every
   `RefScope.RESOURCE` kind).
3. Spec page in `docs/format/`, a resource in `examples/showcase` (the golden
   test fails if a kind isn't covered, binary kinds excepted), invalid cases.
4. The plugin: the session service that reloads it (`RuntimeService.reloads`; startup
   fails without one) and whatever runs it.
5. The editor: its view in `editors/registry.tsx` (a JSON kind without one is
   a type error) and its editor.

Nothing else: the contract generator (schemas, `KINDS`, the kind id types),
`classify`, `newResourceFiles`, `loadProject`'s outline, the CLI and the
golden tests follow from the registry. `KindsTest` makes, classifies and
loads every registered kind.

## Tests

`GoldenTest` checks every `examples/*` project loads clean (with its packages,
`loadTestProject`) and is canonical, that its `netherforge.lock` is what its
packages resolve to, and that every `packages/format/testdata/invalid/*` case
produces exactly its `expected.json`. `PackagesTest` does the same for
`packages/format/testdata/packages/<case>/` (several projects side by side,
`app` loaded). Both run on the JVM and in JS. See the testing skill.
