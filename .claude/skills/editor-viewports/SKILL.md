---
name: editor-viewports
description: The editor's shared stage pieces (apps/editor/src/editors/shared) - transforms from format (usePose, the gizmo's one inverse), Viewport3D and the app's one moved canvas, scenes kept while their tab is hidden, the camera as view state, the Timeline (time models, keys, keyboard, playback), KeyedList and Gallery, block models from the client cache and Fit to display, map previews in a worker, and glyph advances for text measuring. Read before touching editors/shared/viewport, editors/shared/timeline, minecraft/client or editors/map.
---

# The editor's viewports and shared stage pieces

Part of the editor's guide: the editor-ui skill has the layers, the registry, the store's invariants and testing, and a section named here that isn't in this skill is in one of the others it lists.

## The viewport gets transforms from format

Never compose transforms in TS. `usePose` (`editors/centity/pose.ts`) poses
the document with `centityPoser` (the server's own `Composer`, compiled once
per model) and every node is a
`<group matrixAutoUpdate={false} matrix={world}>`. A model with errors shows
the latest model in its undo history that composes, at the same clip and
time, with a note (nothing kept by path: it's the document's own history). The one inverse is the gizmo
(the shared `Gizmo` hands the centity a dragged stand-in; `editors/centity/gizmo.ts`): `local = parentWorld⁻¹ · world`, decomposed as XYZ Euler
degrees, which matches `Matrix4.fromTrs` (`T·Rx·Ry·Rz·S`); its test checks
that against `poseCentity`. While a clip is previewed and the node's channel
has a track, a drag keys that channel at the playhead instead of editing the
base transform.

## Viewports (`editors/shared/viewport/`)

**The build screen** (`editors/builds/`: not `build/`, which `.gitignore` leaves out): a build's parts are read through `readProjectBytes` and drawn as a 2D
picture (`PictureView`, from `@netherforge/structures/picture`'s `StructurePicture`: from above, isometric, or a
slice; the same pixels `netherforge preview` writes), never as a 3D mesh, so a big one stays quick; `ops.ts` cuts a
capture's box into part boxes (each one bridge `save_structure`, all captured before any is written) and works out
the size and gaps. `ImportSchematic` (shared with the structure screen, which takes only what fits in one file) reads
a `.schem`/`.litematic` with `@netherforge/structures/schematic` and writes `parts.ts`'s files through
`workspace.writeBinary`, deleting parts an old grid had past the new one.

**The structure screen's generation section** (`editors/structure/Generation`):
a structure's `structures/<id>.json` (format's `StructureGeneration`, the
companion document in `KINDS.structure.companion`) isn't a tab of its own, so
the section loads it with `loadDoc`, edits it with `edit` and saves each edit at
once; the switch writes a canonical new file (`canonicalizeModel`) or deletes it.
Its jigsaw pools (each piece a project structure and a weight, a projection per pool) are edited there too (`ops.ts`: `addPool`, `setElement`…). `isProjectJson` lets a binary folder's
format-owned documents through, and rename/duplicate/delete of a structure carry
the companion (`ExplorerFolder.companion`, `resourceActions`).

Every 3D view (a centity, a particle effect, a cutscene, a structure, a map)
is a `Viewport3D`: the editor gives it its document's
`path` and kind, a `scene` (its content, its `home` camera, grid, lens,
background, `onMissed`) and lays its toolbar and notes over it
(`ViewportToolbar`, `ViewportNote`). The shared parts are the same
everywhere: the background, an ambient light, the grid (`BLOCK_GRID`, or
the scene's), orbit controls with damping, the `Gizmo` (a stand-in at a
world matrix, one undo gesture per drag, the release not read as a click
into nothing), block and item models (`minecraft/client/models.tsx`) and
**Copy picture** (the scene drawn and read in one go, to the clipboard).

- **One canvas.** The workbench mounts `ViewportHost` around the editor
  area: it keeps the scenes (`scenes.ts`, a store with no three.js in it)
  and renders the app's one `<Canvas>` (`ViewportCanvas`, loaded with the
  first scene) through a portal into an element of its own, which it moves
  into whichever viewport is on screen (`attach`). React never sees the
  canvas move, so it isn't remounted; it sits inside the viewport like any
  element, under its toolbar and notes, and `[data-viewport] canvas` finds
  it. Each document's scene is an r3f `createPortal` with its own scene,
  camera, raycaster and pointer; only the active one is drawn (a
  priority-1 `useFrame`) and hit (`setEvents({ enabled })`), and its orbit
  controls alone listen. With nothing on screen the frame loop stops.
- **Why not a canvas per viewport**: a browser keeps a handful of WebGL
  contexts (WebKit and Chromium drop the oldest past 16), and a context
  made again on every tab switch uploads every texture and compiles every
  shader again. drei's `View` (one full-window canvas scissored under DOM
  elements) needs every element over a viewport to be transparent and puts
  the canvas over or under the toolbars; and keeping inactive editors
  mounted (paused with `frameloop="demand"`) still holds a context each and
  every editor's inspector portal at once. Moving one canvas has neither
  problem.
- **Inactive tabs keep their scene.** `Viewport3D` hands the store its scene
  on every render (`show`); when its tab is hidden the editor unmounts but
  the scene stays, with the last content it was given, undrawn and deaf,
  until no tab has its path (`retain`, following the tabs). So whatever a
  scene builds is built in the scene, keyed by what it's built from: the
  structure's mesh (by the file's stamp) and the map's
  `TerrainClient` (the map follows the orbit point through the scene's
  `controls`) survive a tab switch; a scene reports what the DOM shows
  (faces drawn, chunks loaded) through a callback it calls again when the
  tab comes back. Scene content may use hooks and the app's stores (r3f
  bridges React context), and must ask `useScene().active` before
  listening to the pointer itself (`Gizmo` does). A scene that throws shows
  "The preview failed" and is tried again when its viewport comes back.
- **The camera is view state.** Each 3D kind's view has `camera:
CameraPose | null` (null is the scene's `home`; `structure` and
  `map` register views for it, the map with its `radius`). The
  slot puts the view's camera on the camera when it differs from what it
  last showed, writes where the user leaves it when a drag ends and again
  once damping settles (`SETTLE_MS`), and drops leftover momentum when it
  places one. A rename moves the view, so the camera follows; Spawn sets
  `camera: null`.

## Timelines (`editors/shared/timeline/`)

`Timeline` is the keyframe strip under a preview, for any editor and
either time model (`time.ts`: `seconds` for a centity's clips and a
cutscene's camera, `ticks` for a particle effect; snapping, the ruler's
marks, a tick's step and how a time is written): a toolbar (the editor's
`leading` controls, play/pause and its time, the editor's `toolbar`), a row
per track (`TimelineTrack`: keys, a `band` drawn under them such as an
emitter's window and bursts, `nested`, `onSelect`, `onAddKey` on
double-click), the playhead across them and the editor's key fields
beside them. The editor owns the keys: the timeline reports `onMoveKey`
(which returns the key's index after the re-sort, so a drag keeps hold of
it), `onDeleteKey`, `onSelectKey`, and wraps each drag in `gesture`.

- **Keyboard.** The playhead is react-aria's `Slider` (its track is the
  ruler: press or drag on it, arrows a tick, Home/End). Each key is a
  `role="slider"` moved by react-aria's `useMove`, so pointer and keyboard
  share one path: Left/Right a tick, Shift ten, Delete removes it (not the
  editor's Delete), focus (Tab or a press) selects it; focus follows a key
  the arrows moved past another. A key's shape says its easing (diamond
  linear, square step, circle the others; `data-easing`). Pressing on a
  row seeks there, so a double-click keys a curve where it was clicked.
- **Keys** (`keys.ts`): `putKey` (in time order; a key at a time that has
  one replaces it), `moveKey`, `removeKey`, `setEasing` (linear by leaving
  it out), on any `{ time, easing? }`; `centity/ops.ts` and
  `particles/ops.ts` apply them to their own tracks.
- **Playback** (`usePlayback`): every animation frame while playing, the
  editor gets where the playhead was when playing began and the seconds
  since, and applies its own rules (a clip loops, holds or stops; the
  particle preview counts whole ticks at 20 a second).

## Keyed lists and galleries

- **`KeyedList`** is an outline pane of entries kept by name (a centity's
  animations, a particle effect's emitters): add, select, inline rename
  checked against the name rule and the names taken (`nameProblem`),
  duplicate, delete, one context menu, an optional `lead` row that isn't an
  entry (the effect's own), and each row's `data-path` (`emitters.ring`).
- **`Gallery`/`GallerySection`**: a gallery split into sections, each a
  header with its count and actions over react-aria's `GridList` in a grid
  layout (one tab stop, arrows between cards, Space or a click picks; a
  card is a `row` with `aria-selected`).

## Block models from the cache

The import (Rust) copies the jar's `assets/minecraft/{blockstates,models,items,textures,font,particles}`
into the per-user cache; `backend.assetUrl(version, 'assets/minecraft/…')` serves them.
`minecraft/client/assets.ts` fetches and caches JSON per version; `packages/models` (`model.ts`, shared with
the netherforge command's `render`) resolves
block state → blockstate file (best variant match, or every matching
multipart part; game-data defaults fill unspecified properties) → model
parent chain (elements inherited, textures merged, `#vars` resolved);
its `geometry.ts` bakes elements (UVs, face rotation, element rotation +
rescale, blockstate x/y about the centre, per-face shade) grouped by
texture; `minecraft/client/render.ts` builds meshes from the same resolved models the package's `icons.ts`
bakes for the command (`itemModel`, `packModel`, `cubeModel`, `placedModels`), caches them, and loads textures nearest-filtered
(first frame of animated strips, magenta chequer if missing); `minecraft/client/models.tsx`
draws them in a scene (`BlockModel`, `ItemModel`). No client
assets → placeholder cubes, never an error. Items: block-like models render
as their elements, generated models as a flat quad; text displays are a
canvas texture laid out by format's `layoutText` (the game's wrapping and
widths, so it fills exactly what Fit measures), coloured per run by
`styledChars`; only the font itself is the browser's.

**Fit to display** (`editors/centity/fit.ts`) uses the same resolution, then
`minecraft/client/boxes.ts` turns elements into block-unit boxes (rotations applied,
flat elements one pixel thick, contained boxes dropped), writes `boxes` and
`fittedTo` = `fitKey(display)`, which is format's own
`CentityValidator.fitKey` (canonical block state) through the JS exports. Model boxes from the game-data cache win when present.

## Map previews

`map/MapView` draws a map's overworld around its spawn, a disc
of chunks whose radius the user picks (`MAX_RADIUS`), and follows the
OrbitControls target: when it enters another chunk, the view moves.

- **Where it runs.** Everything per block (region sector table, chunk
  decompression including LZ4 and `.mcc` files, NBT, palette unpacking,
  meshing with `minecraft/client/blockMesh.ts`) is `TerrainCore` in a web worker
  (`terrain.worker.ts`). The main thread only answers its two questions
  over messages: a region file's bytes (`readProjectBytes`, so path-scoped
  `nfproject://` like every binary resource; no Rust command) and block
  states baked with the client's models (`minecraft/client/bake.ts`, remapped into the
  `minecraft/client/atlas.ts` atlas, since a chunk of 25 textures would otherwise be 25
  draw calls). `TerrainCore` takes its I/O as functions, so vitest drives
  it, and `TerrainClient` over an in-memory channel, without a worker.
- **Memory follows the view.** The worker keeps decoded blocks for the
  drawn disc plus a ring of one (border faces are culled against the
  neighbour chunk's blocks, so a seam between two solid chunks has no faces)
  and region files only while a kept chunk is in them; everything else goes
  when the view moves. `TerrainClient` keeps meshes `KEEP_MARGIN` chunks
  past the radius and reports drops, so the worker re-sends only what the
  main thread no longer has. Real terrain is about 4000 faces a chunk
  (~100 bytes each): radius 6 is ~550k faces, 10 about 1.4M.
- **What it doesn't draw**: chunks whose `Status` isn't full (the game
  doesn't either), fluids (no block model), block entities' special
  renderers, biome tints (plains colours, as everywhere in the editor), and
  glass doesn't cull against glass.
- A missing chunk, region file or `.mcc` is "nothing there"; an unreadable
  chunk is reported (`Unreadable chunks`) and drawn as nothing, so the faces
  toward it show. A map with no `.mca` files says so instead of a view.

## Glyph advances and text measuring

The import computes the default font's advances (Rust, see tauri-backend);
`refreshGameData` fetches them with `backend.glyphAdvances(version)` into
`glyphAdvances`, kept apart from `gameData` (the server's export, which is
all validation reads: `GameData` holds server facts only). Text measuring
(`layoutText`, `fitTextHitbox`) takes the advances alone; `core/format.ts` caches
their JSON per table, since a call per render would otherwise re-serialise
it. Without an import, format estimates unknown glyphs (6 px, CJK 9), so Fit
for a `fixed` text display still works and says it's approximate.

The same advances are written into the project as `fonts/default.json` for
the server (`core/defaultFont.ts`; see the game-data skill): after every
`refreshGameData` and whenever that file changes on disk, the workspace
writes format's canonical text if it differs, never over unsaved edits to it.
A failed write is an `editor.default-font` problem until one succeeds.
