---
name: editor-resource-editors
description: What is specific to each resource editor in apps/editor/src/editors - menus (slots, skins placed from format's numbers), dialogs, resource packs (pictures' pixels, sounds), items and project items, recipes (shaped grids), text (MiniMessage via format), loot tables, advancements, migrations, particle effects, cutscenes, terrains (the tiled map, the slice, the preview workers, terrain scripts), screens, screen fonts and videos. Read before changing one of those editors; the editor-ui skill has what they share.
---

# The editor's resource editors

Part of the editor's guide: the editor-ui skill has the layers, the registry, the store's invariants and testing, and a section named here that isn't in this skill is in one of the others it lists.

## The resource editors (menus, dialogs, resource packs)

All three are model documents through the same store, so undo, gestures,
save → canonicalize → write → hot reload, validation and external changes
are the generic ones. What's specific:

- **Selection** is the document's view (`usePrimary(kind, path)`), typed
  per kind: a slot index (`13`), `{ list: 'buttons', index: 1 }`,
  `{ kind: 'skins', key: 'shop' }` / `{ kind: 'textures', key: 'gui/shop.png' }`.
  An outline's row ids are the kind's `key` of each item.
  `useFocusRequests(path, route)` turns a problem's JSON path into
  that selection and focuses the field whose `data-path` matches
  (`slots["13"].item.count`, `buttons[1].key`, `skins.shop.ascent`;
  `editors/shared/focus.ts#fieldPath` writes paths the way format does).
- **Menu**: `WindowFrame` draws the vanilla window from `window.ts`
  (the game's container sizes and slot positions, which are layout, not
  data), the skin where the server draws it, then the slot buttons. Drag a
  slot onto another: `moveSlot` swaps them whole, one
  `edit()`, so one undo step. Slots past a shrunk window are kept (format
  flags them) and listed.
- **Skins** are placed with `compileResourcePacks` numbers only: the title starts at
  the window's title position (centred for dispenser/dropper/crafter, on the
  words alone), the picture is drawn `offset` from it, `ascent` above a
  baseline 7 below the title's top, `height` tall, width by aspect. The
  prefix is zero wide (format's `titlePrefix` moves back by the skin's
  advance), so the words start at the title position, over the art.
  `placeTitle` assumes that even before the picture is measured. On a
  centred type the server moves the skin back by the words' width when it
  can measure them (no `font.needed` problem for the menu: `MenuWindow`
  passes `measured`), so the preview draws it where a left-aligned title
  would; otherwise the art moves with the words, and `WindowFrame` warns.
  Never invent a second layout.
- **Resource pack pictures' pixels**: the app's resource packs store (`core/store/resourcePacks.ts`, one
  per app, worked out once per change however many components show resource packs)
  loads every skin/glyph texture (`loadImageInfo`, cached per URL), scans
  alpha (`opaqueWidth`), and passes the facts to `compileResourcePacks`, which owns
  the advance rule (`PackFonts.bitmapAdvance`). Don't compute advances in TS:
  an unmeasured glyph is left out and format decides.
- **Dialogs**: `arrangeButtons` decides footer vs columns per type;
  message text is wrapped with format's `layoutText` (the import's glyph
  advances) and drawn line by line from its source ranges, so breaks match
  the game. Widgets are drawn at the game's sizes in the editor's style.
- **Resource packs**: `ResourcePackEditor.tsx`, its inspectors beside it (`EntryInspector`,
  `TextureInspectors`, `SoundInspector`, `HudInspector`, and `shared.ts`). A `Gallery` with a section per kind (skins, glyphs, item models,
  tooltips, equipment looks: `ENTRY_KINDS`, each kind's texture fields from
  format's `RESOURCE_PACK_TEXTURE_FIELDS`, so a kind with several layers shows a
  picker per layer) plus every PNG under `textures/` and every sound. Import is
  an `<input type=file>` → bytes → `workspace.writeBinary` (works in Tauri
  and the memory backend alike). Pictures load through
  `backend.projectFileUrl(path, fileStamps[path])`; the store bumps
  `fileStamps` on our writes and on watcher events, so images reload.
  The resource packs store reads each pack.json from the open document first (unsaved
  edits show everywhere at once), else disk; `useResourcePacks.ts` holds its
  selectors (`useCompiledResourcePacks`, `useResourcePackFiles`, `useGlyphMap`). Sounds are a
  gallery of their own: every `.ogg` under `sounds/` (`soundFilesOf`) plus
  `pack.json`'s `sounds` entries (`soundEvents`), picked as `sounds:<key>`,
  with an `<audio>` per file and volume/pitch/subtitle fields that drop an
  entry once it's empty (`setSoundValue`); "Import OGG…" checks the `OggS`
  header.
- **Items** (`minecraft/item/`): one `ItemEditor` for slots and dialog bodies;
  `onEdit(recipe)` applies to the item inside whatever owns it. `ItemIcon`:
  a resource pack `itemModel` draws its `guiTexture ?? texture` (project file), or for
  one drawn as a resource pack block (`block`) the look's faces (`lookOf` in
  `minecraft/block/look.ts`, shared with the block editor) on vanilla's
  `block/cube` (`blockLookPicture`); else
  the client's sprite layers, or its 3D model rendered once per item by
  `minecraft/client/iconRender.ts` with the model's `gui` display transform; else a
  labelled placeholder. A reference to nothing falls back to vanilla, as
  the client does. A stack naming a project item (`{ item: "ruby" }`) is
  drawn as `stackLook(stack, definition)`: the definition fills what the
  stack leaves out, the kind is always the definition's (format's
  `ProjectItems.resolve` rule). `minecraft/item/projectItems.ts` reads project items
  and recipes as they are now (open document first, then disk; parsed once
  per text) for icons and "which recipes use this item". `ItemEditor`'s
  `look` hides the stack's own fields (format's `ITEM_STACK_FIELDS`), and
  `projectItems` offers the "Project item" select (`setProjectItem` drops the
  kind, which the definition owns).
- **Project items** (`editors/item/ItemScreen.tsx`, `items/<id>/item.json`): the
  shared form as `look`, a 128 px `ItemIcon` and tooltip, the item's one
  script (`newScript('item')`), and the recipes that make or take it.
- **Recipes** (`editors/recipe/`, `recipes/<id>.json`, a file kind): a station per
  type in the game's window style. A shaped recipe is edited as its grid:
  `ops.ts#shapedOf` derives `pattern` and `key` canonically (empty rows and
  columns trimmed, as the game reads a pattern; one letter per distinct
  ingredient; letters already in the key kept; a new one takes its name's
  first free letter). Where the trimmed pattern shows on the grid is view
  state, the `GridOffset` kept in the document's view (`RecipeView.offset`), so a shape
  stays where the user put it. Which fields, categories, group and default
  cooking time each type takes come from format's `RECIPE_TYPES`.
  `IngredientPicker` has three tabs: Minecraft items (`usePickerIds`), a
  typed tag (the game data has no tags; format validates it), project items.
  Problems show on the slot (`data-path` `key.R`, `ingredients[2]`,
  `template`), in a list under the station, and under inspector fields.
- **Text** (`minecraft/text/`): there's no MiniMessage parsing in
  TypeScript. `styledChars` is format's `styleText` (its `MiniMessagePass`,
  the same pass `TextMetrics` measures, cached by text) with the preview's
  base style under it, so a character index from `layoutText` is the same
  character here; every `<…>` draws nothing except `<glyph:ui/coin>`, one
  `StyledChar` with `glyph` set (a run of its own), drawn as the picture.
  A colour or decoration a preview gets wrong is fixed in format's pass.
  `useGlyphMap` keys glyphs by character and by both ways a tag can name
  them (`ui/coin`, `<namespace>:ui/coin`);
  `glyphAdvancesOf(glyphs)` is what `layoutText`/`fitTextHitbox` take to
  measure tags. There are no `<glyph:` completions in text fields yet.

- **Loot tables** (`editors/loot/`, `loot/<id>.json`, a file kind): pools in
  the outline (`KeyedList`, ids), the selected pool's entries as cards (the
  item's `ItemIcon` and tooltip, weight and its share of a pick from
  `ops.shares`, count, conditions; move and remove), add buttons per entry
  type, and the inspector for the pool (rolls, bonus rolls, conditions) and
  the selected entry (the shared `ItemEditor` with `look`, since an entry's
  count is its own; the table it rolls; count, weight, quality, conditions,
  a tool through the recipe editor's `IngredientPicker`). Selection is a
  `LootPick` (`{ pool, entry? }`) in the view, beside the preview's `seed`.
  "Roll" is format's own roller through `rollLoot`, over every loot table's
  current text, the packages' as read, keyed by full name (`showcase:treasure`,
  `library:gems`): format reads each in its own namespace, as the server
  does, so drops name items in full. No rolling rules in TypeScript. A table
  entry offers the project's tables and its dependencies' exported ones
  (`useNameableIds('loot_table')`); a package's table opens read-only in its
  own namespace (`idOf` takes the id from the package path).
- **Advancements** (`editors/advancement/`, `advancements/<id>.json`, a file
  kind): the display as a preview card (frame, `ItemIcon`, MiniMessage title),
  criteria as cards, and requirements summarised; criteria in the outline
  (`KeyedList`, ids), selection is the criterion name. The inspector edits the
  advancement (parent from `useNameableIds('advancement')`, experience), the
  display (an enable toggle; icon from a game item or a project item, never
  both), requirements (custom groups as comma-separated names) and the selected
  criterion (trigger from the game data's trigger registry, conditions as JSON
  the game reads, committed only when they parse as an object). A reload that
  says `restart` makes the run store `restart()` the dev server (providers'
  `reloaded` hook; the MCP `reload` tool does the same); the memory backend
  says it after `testReloadRestarts()`.
- **Migrations** (`migrations/NNN_name.sql`): a file kind with
  `contents: 'sql'`, like a module's Lua it has no view in `KIND_CONTRIBUTIONS`
  (the type allows `lua | sql` without one) and no kind tab; it opens as
  its file in the `sql` file tab (`SqlEditor`, highlighted by Monaco's own SQL
  tokenizer, registered in `monaco.ts` beside Lua's; no SQL language service
  is loaded), which `tabTypeFor` picks for any `.sql`.
- **Particle effects** (`editors/particles/`): the preview never invents points:
  `particleEffectSampler` (format's `EffectSampler`, seed 1) says what each
  tick spawns, and `simulate.ts` only approximates what the client does after
  (lifetime, drag, fade). `Simulator.at(tick)` steps forwards and replays from
  tick 0 on a seek, so scrubbing is deterministic. A curve replaces its
  constant in every op (except `rate`, which marks the emitter rate-driven);
  a new key is seeded with `particleCurveAt`, the sampler's own reading.
  "Play on server"/"Stop" (`PlayOnServer.tsx`) are enabled while the bridge
  is connected: Play saves a dirty document first (its reload reaches the
  plugin before the play, one connection in order), then sends
  `play_particle_effect` with the timeline's loop toggle through
  `run.playParticleEffect`; Stop sends `stop_particle_effects`.

- **Cutscenes** (`editors/cutscene/`): the camera path of `cutscenes/<id>.json`
  on the two shared pieces. The viewport (`CutsceneViewport`, a `Viewport3D`)
  draws format's own `CameraPath` through `cutsceneDirector(id, text)` (JS
  export of `CutsceneDirector`: `shot(time)` the camera at the playhead and
  `trail(count)` the path, both a `CutsceneResult`, `failed` with the problems
  when the file doesn't compile): the path as a line, a marker per position key
  (a click selects it), the camera as a frustum at the playhead, framed from the
  path's bounds until the user moves the camera. The timeline (`CutsceneTimeline`,
  the shared `Timeline` in seconds, as a centity's clip) has a row each for
  `position`, `rotation` and `cues`, the keys' fields beside it (`editor` slot:
  time, value or yaw and pitch, easing, a cue's event, text and duration, each
  with a `data-path` like `camera.position[2].value`, so a problem focuses it),
  and **Key position**, **Key rotation** and **Add cue** at the playhead (a
  key is seeded with `shot`, the camera as it is now). The view (`view.ts`) is
  the playhead, whether it plays (`usePlayback`, stopping at the end) and the
  camera; the selection is a `{ track, index }` key (none: the inspector shows the
  cutscene's `length` and `skippable`). `ops.ts` has the pure edits (on the shared
  `keys` helpers, so ordering and easing are the centity timeline's rules) and
  `lookDirection`, the frustum's aim (yaw 0 faces +Z, 90 faces -X, positive pitch
  looks down). Tests: `ops.test.ts` (ops and the director), `e2e/cutscene.spec.ts`.
  There's no "play on the server" button: a cutscene needs a player, which a
  script (or a bot's command) provides.

- **Terrains** (`editors/terrain/`): `terrain/<id>.json`, drawn by the server's own generator. The stage
  has two canvases made from format's `terrainPreviewer(id, text, structures)` (JS export of `TerrainPreviewer`:
  `map(...)` and `slice(...)`, each a `TerrainPreviewResult`, `failed` with the problems when the file doesn't
  validate for its shapes; both take the world's `minY`/`maxY`: the heights the editor bar's **Preview heights** picker chose, `heights.ts`'s `heightChoices` (the overworld's, then each `netherforge.json` world naming this generator and a project dimension type, read from the manifest and `dimension_types/<id>.json` as they are now; references resolved by format's `resolveReference`), the view's `heights` (a world's name, `''` the overworld, null the first world, else the overworld)): a **map from above** (filling the editor over the slice, a `Group` split; heights lit from the north-west, the sea blue,
  biome areas in `areaColor`s that avoid blue) and a **slice** through whole generated chunks along a line (128 to 1024
  blocks, centred on `along`, fitted to its panel; run-length columns; air transparent, the sea translucent, the project's own blocks pink so ore of a custom
  block stands out, stone grey, other blocks a colour by palette place: the editor ships no pictures, so a legend
  names each). Where surface and sea-floor decorations start is marked on the map in `decorationColor`s with a
  legend (`Decorations`; only where a tile is close enough, `decorationsShown`). **The map is tiles** (`tiles.ts`):
  64 by 64 cells of a power-of-two `step` on a grid fixed in the world, `stepFor(zoom, detail)` picking the level
  (the view's `zoom` is screen pixels a block, `detail` the pixels a cell aims at). `MapView` draws every cached
  level coarse to fine on one canvas, so a drag (pointer), wheel or pinch (`gesturechange` on WebKit) moves what's
  drawn at once and writes `x`/`z`/`zoom` to the view when the gesture ends; `wantedTiles` (a coarse level first,
  then the shown one, nearest the middle first, then a ring) goes to the `TileScheduler`, which keeps a few workers
  (`tileLaneCount`) on the first tile wanted that it hasn't got for the current file and world (`gen`), keeps an
  older generation's tile drawn until replaced, and stops on a `failed` answer or a worker error until the source
  changes. A click cuts the slice there (`alongX`/`at`/`along`), the pointer's cell says its ground, biome and
  decorations in a readout (`terrain-map-readout`; the slice's likewise). **The preview runs in workers** (`worker/`):
  `core.ts` (`createPreviewCore`: the previewer compiled once per `fileKeyOf(file)`, answering `tile` (a map one cell
  wider cut back by `cropMap`, its pixels made there, so tiles meet without a seam) and `slice`; a request carries
  the file only when its worker may not have it, else `NEEDS_FILE`), `preview.worker.ts` (Comlink), `client.ts`
  (`terrainPreviewWorker()`; `Lane`, one worker asked one thing at a time, started over when it fails;
  `LatestPreview`, the slice's newest-only queue, behind `useSlicePreview`). The structures a file's decorations name (the
  project's own; a package's isn't drawn) are read from their `.nbt` (`readProjectBytes`, `readNbt`,
  `parseStructure`, keyed by their stamps) and sent with the request as `TerrainStructureInput`s. The seed (a number, "Random
  seed"), the map's centre, zoom and detail and the slice's line and width are the view (`view.ts`: not in the file, not undone;
  there's no selection). The inspector (`inspector/`: `Inspector.tsx` and a module per section, `fields.tsx` and `noise.tsx` what they share) has a section per part in the order a column is made
  (Terrain with its named noises and blend, and its 3D ground (`DensityFields`: the `densityNoises` collection,
  `NoiseEntries` with a squash field, the islands with their own `AreaFilter`; `setDensity` off takes the areas'
  densities too), Ground's layers and stone, Floor, Caves, Ores, Decorations, Biomes with
  each area's own layers and terrain (its own noises: `NoiseEntries` with the `{ area }` collection, its own 3D
  noises with `{ area, density: true }`), the climate
  noises and the borders' jitter, Structures). Everything that names a block is a `BlockPicker` (the game's, the
  project's, or for a decoration a structure; `setBlock` keeps one), and an ore, a cave or a decoration ticks the
  areas it keeps to (`AreaFilter`, `setInArea`; renaming or deleting an area follows into every filter),
  every field with a `data-path` that `fieldPath` makes from the JSON path, so a problem focuses it; named entries
  are added with a name prompt (`nameProblem`, `ID_NAMES`), renamed in place and deleted. `ops.ts` is the pure
  edits (`addEntry`/`renameEntry`/`deleteEntry` by collection, layer lists for the file or one biome area, a
  block named one way, the stone, area filters), the pure pictures are `packages/terrain-preview` (`draw.ts`: `mapPixels`, `slicePixels`, `sliceColors`
  and the colours; `previewer.ts`: the typed `terrainPreviewer`, which `core/format.ts` re-exports; `structures.ts`:
  `structuresNamed`, `structureInput`), shared with `netherforge preview` (apps/cli), which draws exactly what this
  does. NBT reading lives beside it in `packages/structures` (`nbt.ts`, `structure.ts`, and schematics, build
  parts and structure pictures), for the same reason: the editor imports `@netherforge/structures/nbt` and `/structure`. Tests: `ops.test.ts`,
  `packages/terrain-preview/src/draw.test.ts` (against the real JS build), `worker/core.test.ts` (a structure linked from its input, other
  heights), `worker/client.test.ts` (newest slice wins, a lane sends its file once and restarts), `tiles.test.ts` (levels, what's wanted, the scheduler's generations and failures), `e2e/terrain.spec.ts` (the
  preview following a base height and a seed, the example's decorations marked (its tree read from the seed's
  `.nbt`), an ore of a project block added, a decoration added and kept to an area that's then renamed, a new
  generator, a mistake naming why nothing is drawn). The memory backend's seed bundles `.nbt` files as bytes.
  **A file's script** (W5.6) runs in the same worker: `core.ts` sees a `script` (`hasScript`) and first awaits the
  Lua (`createPreviewCore(loadLua)`, default `loadBrowserLua` from `@netherforge/terrain-preview/lua.browser`:
  wasmoon's `glue.wasm` as a `?url` asset of the bundle, loaded once; the CSP's `'wasm-unsafe-eval'` is what lets a
  page compile WebAssembly, and only that). The request carries `sources` (`terrain/<id>.lua` and every module's
  Lua, `scriptPaths`) with a `sourcesKey`: `useScriptSources` in `TerrainEditor` reads an open document's text as it
  is (so typing in the script redraws) and the rest from the backend by stamp. The previewer is kept while file,
  structures and sources are unchanged (its Lua state with it) and `close()`d when replaced. What the script failed
  at (`TerrainMap/TerrainSlice.scriptErrors`: stage, message, file, line) shows over the pictures (`ScriptErrors`,
  an alert named "The script failed", each a link opening the file at its line); the pictures are then the file's own
  result there, as on the server. The inspector's **Script** section (`ScriptSection`) turns `script` on (an empty
  object; `terrain/<id>.lua` made from format's `newTerrainScript()` when it isn't there) or off (`setScripted`),
  opens the script, and edits its budget, its blocks (`setScriptBlocks`) and its noises (the `scriptNoises`
  collection of `ops.ts`). The script's API stubs are `.netherforge/luals/terrain.lua` (`apiStubs.ts`, beside
  `nf.lua`): classes on locals, no `nf`. It runs for an untrusted project too: it's Lua in WebAssembly with only the
  generator's host functions and a budget (tauri-backend's threat model). Tests: `worker/core.node.test.ts` (Node:
  jsdom looks like a browser and Node at once to wasmoon, which then finds its WebAssembly as neither, so a test with
  Lua loads Node's, `loadNodeLua`), `e2e/terrain.spec.ts`'s script flow (the template made, a module required, a
  failure said and opened).

## Screens, screen fonts and videos

`editors/screen/` draws a screen's maps as the wall a player faces (columns, rows, its background) and edits its
size, placement, viewers, autoplay video and script; `editors/screen_font/` draws a pixels font's glyph on a grid
(each cell a toggle button) with a sample line, and shows a bitmap font's picture; `editors/video/` plays the
source (`projectFileUrl`), shows `videoInfo` (and `staleness` against the file) and runs `convertVideo` with its
`onVideoProgress`. Each has pure `ops.ts` (or helpers) and a component test. The memory backend's `convertVideo`
needs `ffmpeg: true` and game data with `mapColors`, and writes a real `.nfv` header (so `videoInfo` reads the
example's `bounce`).
