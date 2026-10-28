---
name: resource-packs
description: How NetherForge turns resource_packs/ into one Minecraft resource pack and gets it to players - PackLayout.build, the pack format from the server's version.json, deterministic zips and SHA-1, the embedded HTTP server or an external URL, sending on join and only when the hash changed, rebuild on reload, skins as title glyphs, glyph characters for text (the <glyph:ui/coin> MiniMessage tag, nf.text.glyph), resource packs in the project's namespace, image facts (opaque width → bitmap advance). Read before touching format's resourcepack/, apps/plugin/runtime/.../pack, the resource-pack config, or anything that draws a skin or glyph.
---

# Resource packs

Spec: `docs/format/resource-pack.md`. Format side: `packages/format/.../resourcepack/` (`ResourcePackFile`,
`CompiledResourcePack`, `PackFonts`, `PackLayout`, `pngSize`; `text/GlyphTags`). Plugin side:
`apps/plugin/runtime/.../pack/` (`Packs`, `PackZip`, `PackServer`), delivery via
`Platform.resourcePacks`.

## Resource packs live in the project's namespace

A resource pack is `resource_packs/<id>/`, and its entries are referred to as `<id>/<key>`
(`ui/coin`), like every reference (`acme:ui/coin` for a package's; the
project-format skill). `PackLayout` builds every resource pack into the **project's**
namespace under its id: textures at `assets/<ns>/textures/<id>/…`, skins'
font `<ns>:<id>/gui`, item models `<ns>:item/<id>/<key>` with the item
definition at `assets/<ns>/items/<id>/<key>.json` (so `itemModel: "ui/ruby"`
is the client's `<ns>:ui/ruby`), tooltip sprites
`<ns>:tooltip/<id>/<key>_<part>`, equipment looks `assets/<ns>/equipment/<id>/<key>.json`
(the `equippable` asset `<ns>:<id>/<key>`) with each layer's texture at
`assets/<ns>/textures/entity/equipment/<layer>/<id>/<key>.png` (`CompiledResourcePack.equipment`,
`EquipmentAssetDef.layers()`; not copied under `textures/<id>/` as well), sounds `assets/<ns>/sounds/<id>/…` with
every resource pack's events in one `assets/<ns>/sounds.json` as `<id>/<key>`. A
reference resolved (`ResourceKey`) is literally the client's key, and two
projects never collide. A resource pack may be called anything,
`minecraft` included: the namespace is the project's, and that's what
can't be `minecraft`.

The packages a project depends on build into the **same** resource pack, each
resource pack in its package's namespace (`assets/library/…`): `compiledResourcePacks` keys a
package's resource pack `ns:id`, its glyphs are numbered after the project's (so the
project's keep the characters the editor previews), and `CompiledResourcePack.key`
says where its files are (`texturePath`/`soundPath` of a key `ns:id` are
package paths). The runtime finds the resource pack an entry is in with
`Packs.packOf`.

## The pipeline

1. `Projects.load` compiles **all resource packs together** (`CompiledResourcePack.compileAll`):
   glyphs merge into the default font and are numbered project-wide, skins are
   numbered per resource pack in its own `<ns>:<id>/gui` font. Any resource pack with errors → no
   compiled resource packs → the plugin **keeps its last good build** (and characters).
2. `Packs.rebuild` calls `PackLayout.build(packs, format, description,
textureSize)`. The **format** is `GameData.resourcePackFormat`, which
   `PaperGameData` reads from the server jar's `version.json`
   (`pack_version.resource_major/minor`, falling back to older shapes). No
   format → no resource pack, a `runtime.pack-format` warning.
3. `PackZip.zip`: entries sorted, every entry `setTimeLocal(2000-01-01)` (DOS
   local time with no time-zone conversion and no extra fields), one
   compression level. Same files → same bytes → same SHA-1, on any machine.
4. Published: `PackServer` (JDK `HttpServer`, daemon threads) serves
   `GET/HEAD /<sha1>.zip`, keeping the current and previous build; the URL is
   `<publicUrl or http://host:port>/<sha1>.zip`. With `externalUrl` the plugin
   instead writes `plugins/NetherForge/resource-pack.zip` (atomically) for the
   owner to upload and sends their URL.
5. Sent with `Player#setResourcePack(PACK_ID, url, sha1, prompt, required)`:
   on join, and to everyone online after a rebuild that changed the hash.
   `Packs.sent` remembers each player's hash, so nothing is re-sent needlessly
   (the client would skip the download, but not the "applying" screen).

Config: `RuntimeConfig.pack` (`PackConfig`). Dev server: loopback, a free
port. Production: `resource-pack:` in `config.yml` (bind `0.0.0.0`, port 8163,
`public-url` defaulting to `http://<server-ip or localhost>:<port>`).

## Videos' sounds

A converted video's sound is in the pack too, though no resource pack holds it: `VideoSound.of(snapshot)` lists
each running video that keeps its sound and has `audio.ogg`, and `PackLayout.build(..., videos)` copies it to
`assets/<ns>/sounds/video.<id>.ogg` (event `video.<id>`) and each piece `audio/<n>.ogg` to `video.<id>/<n>.ogg`
(event `video.<id>.<n>`), all `stream: true`. A pack id has no `.`, so neither can collide with a pack's
`<pack>/<key>`. `PackService` follows `VideoKind`, so converting again rebuilds and resends the pack.

## Sounds

Every `.ogg` under `resource_packs/<id>/sounds/` is the sound event `<id>/<path without
.ogg>` (the game's `<ns>:<id>/<path>`); `pack.json`'s `sounds` (`SoundDef`: `files`, `volume`, `pitch`,
`stream`, `subtitle`) only refines an event or adds one that picks among files.
`PackSounds` owns the rules (key = id segments joined by `/`), `files` sorts a
resource pack's `sounds/` folder into usable files and problems reported **in the file
itself** (`resource_pack.sound-name` error, `resource_pack.sound-file` warning for non-Ogg;
hidden files skipped), and `events` merges files and entries. A bad sound file
stops the resource pack building like an error in `pack.json`. `PackLayout` copies the
files to `assets/<ns>/sounds/<id>/` and writes every resource pack's events into
`assets/<ns>/sounds.json` (plain names unless volume/pitch/stream need the object
form; `stream: true` is explicit per sound and never guessed from the
file's duration, so the build needs only paths, not bytes). The reference index knows every resource pack sound (`RefKind.SOUND`), so
`world:play_sound` and `player:play_sound` take a resource pack sound by reference
(`ui/click`) and send the game its key; anything else is the server's
(`Effects.soundId`). The editor gets each resource pack's keys in
`ResourcePackOutline.sounds`.

**The game's sound events.** A `sounds` key in the game's namespace (`minecraft:block.stone.break`,
`PackSounds.isGameEvent`) replaces that event of the game's: `PackSounds.replacements` (never among a pack's own
`events`, so references and the outline never see it), `CompiledResourcePack.soundReplacements`, and `PackLayout`
writes it into `assets/minecraft/sounds.json` with `"replace": true` and the pack's files (copied as any of its
sounds). `files` is required (`resource_pack.sound-replace`); with game data the event must be in
`RegistryKey.SOUND_EVENT` (the server's registry, so no `client/vanilla.json` entry), and two packs can't replace one
(`resource_pack.sound-event`). Footsteps, hits and falls are chosen by the client from a block's sound type, so this
is the only way to change them.

## Naming rules

`PackSounds.KEY`/`FILE` (a sound's key, and its file under `sounds/`) and
`ResourcePackFile.TEXTURE_PATH` (a texture's path under `textures/`) are the rules,
built from `Names`' id and path-segment patterns. The editor never re-encodes
them: it gets them, with the fields of each entry kind that name a texture
(read from the `@Ref(RefKind.TEXTURE)` marks), as generated constants
(`RESOURCE_PACK_SOUND_FILE_PATTERN`, `RESOURCE_PACK_SOUND_KEY_RULE`, `RESOURCE_PACK_TEXTURE_PATH_PATTERN`,
`RESOURCE_PACK_TEXTURE_FIELDS`, the extensions), which its resource pack editor's import
prompts and `editors/resource_pack/ops.ts` use.

## Image facts

Only pixels know a bitmap glyph's advance: `PackFonts.bitmapAdvance` =
`floor(0.5 + opaqueWidth × drawnHeight / imageHeight) + 1`, `opaqueWidth`
being the rightmost column with any non-transparent pixel + 1 (one char per
provider, so the cell is the whole image; the game does this in `float`, we
in `Double`). So pixels are part of the source of truth:
`ProjectSource.image(path): ImageInfo?` (`readsImages` says whether it can
answer at all), handed to `CompiledResourcePack.compileAll(files, image)`, which
gives `Skin.advance`/`Glyph.advance` (null when unknown).

- Plugin: `DiskProjectSource.image` decodes with ImageIO (the runtime's
  cached source forwards it). An unreadable skin picture is a `resource_pack.image`
  warning from `Projects.load`.
- Editor: `usePackImages` measures alpha (`opaqueWidth` in
  `core/image.ts`, the only pixel code in TS) and passes the facts to
  `compileResourcePacks(packsJson, imagesJson)`. Its `loadProject` passes none, so
  the editor never shows `resource_pack.image`.

## Skins and glyphs in text

- **Pictures wider than a font sheet.** The client stitches font pictures into 256×256 sheets
  (`FontTexture.SIZE`, checked in the 26.3 jar) and draws a bigger one blank. `PackFonts.picture` cuts a
  wider one into `PackFonts.pieces` equal cells: one bitmap provider whose `chars` row is the pieces'
  characters (`cells`; the game's grid cuts the image, so no PNG is rewritten), and `char` becomes the
  pieces with `PackFonts.lay`'s moves between them, netting the whole picture's advance. So everything that
  inserts `char` and measures `advance` (titles, `<glyph>`, the datapack, the HUD line) needs nothing new.
  Each piece's own advance comes from `ImageInfo.opaqueColumns` (hosts send it for wide pictures). Extra
  piece characters are numbered after every glyph's (skin's, HUD picture's) own, so a picture's own
  character never depends on another's pixels. Too tall, or no even cut: `resource_pack.picture-size`
  (`ResourcePackKind.crossCheck`, from sizes). Glyphs are never bold or obfuscated (both would pull the
  pieces apart), and measuring doesn't add bold's pixel to them.

- A skin is drawn by the window title: `Packs.skinTitle` →
  `<white><font:<ns>:<id>/gui>` + `Skin.titlePrefix` + `</font></white>`, then the
  menu's own title. The prefix is `offset(o) + char + offset(-(o +
advance))`: it nets to zero width, so the words start where a vanilla title
  does, over the art. With no advance there's no return offset (the words
  then start after the art; that's what the `resource_pack.image` warning is about).
  Changing a skin retitles (reopens) the window.
- Centred titles (dispenser, dropper, crafter: their screens centre on
  `font.width(title)`) centre on the words alone, which would move the skin
  with the title's length. The runtime shifts the picture back
  (`titlePrefix(shift)`, `MenuType.skinShift`) using the words' width from
  the editor-written `fonts/default.json` (the client fact resolved into the
  project). Without it, `font.needed` warns and the menu editor's preview
  draws the skin where it will really land.
- After a rebuild whose hash changed, `Menus.refreshTitles()` re-renders
  every window (characters may have moved).
- `<glyph:ui/coin>` in any MiniMessage: the adapter parses all text with
  `PaperText.mini`, which adds a `glyph` `TagResolver` (args joined with `:`,
  so `<glyph:shop:ui/coin>` and `<glyph:'shop:ui/coin'>` agree) asking
  `Platform.bind`'s glyph lookup = `Packs.textGlyph`. It inserts the character
  in the default font and white (untinted, like the editor's preview). Unknown:
  nothing, and one `log.warn` per reference per build. Files are checked by
  format (the reference walker finds the tags in every `@MiniMessage` field:
  `reference.resource-pack-key`/`reference.resource-pack`); `TextMetrics` measures the tag as the
  glyph's advance (its `glyph` lookup; exports take `glyphsJson`, which the
  editor keys by both `ui/coin` and `<ns>:ui/coin`).
  The adapter resolves a tag in the project's namespace, maybe long after the
  script that wrote it ran, so text a script hands the API (`Text`) has its
  tags written in full first, in the writing script's package
  (`PackageNames.text`: `<glyph:gems/gem>` from the library is
  `<glyph:library:gems/gem>`), and one it can't name (another package's
  unexported resource pack) dropped with a warning. `textGlyph` then only looks up,
  exported or not.
- `nf.text.glyph("ui/coin")` returns the glyph's one character (a private-use code
  point), for building text by hand; it's tinted by the text colour. A
  reference the calling package can't name (its own resource packs', and what the
  packages it depends on export) is an error, the index's message; `nil`
  only while resource packs have errors and nothing is built.
- **Moving the cursor** (`text/SpaceTags`): `PackLayout` always ends the default font with the
  `space` provider (`PackFonts.advances`, `spaceAdvance` reads one back), so `<space:N>` (the
  adapter's `space` resolver: `PackFonts.offset`, never bold or obfuscated, nothing while
  `Packs.built` is null) works in any text. `<at:N:from=…:align=…>…</at>` takes no width: the
  adapter's MiniMessage `preProcessor` hands every text to the runtime's `ProjectSession.layoutText`
  (`Platform.bindText`), which is `TextWidth.layout`: it walks the text once, measuring each line
  and each `<at>`'s inside with the style around it, and puts `<space>` moves before the tag and
  where it closes (its `</at>`, a tag closing it, `<reset>`, the text's end). What it can't measure
  stays as written (the `at` resolver only scopes, so the inside draws in line) and is warned about
  once. Previews: `MiniMessagePass` has `Move`/`Overlay`/`OverlayEnd` pieces, `TextMetrics` gives
  an overlay no width, and the editor counts `from` as the cursor (its font isn't the game's).
  A boss bar's `icon` is `<at:-92:from=center:align=right><glyph:…></at>` before its text
  (`BossBars.Bar.shown`).
- `item_model`/`tooltip_style` are references (`ui/ruby`) the adapter
  resolves in the project's namespace when it writes them (`<ns>:ui/ruby` _is_
  the client's resource key) and reads back without it; unknown ones are an
  error when a script sets them. A script's are in its own package's terms
  (`library:gems/gem` to the project, `gems/gem` to the library: see the
  plugin-runtime skill's "Packages in a session"). A package's resource packs are
  built in even when the project has none of its own.

## Block models

`pack.json`'s `blocks` (`BlockModelDef`, `RefKind.BLOCK_MODEL`) is what a block's `model` points
at: `assets/<ns>/models/block/<id>/<key>.json`, a child of `minecraft:block/cube` with each face's
texture (`BlockModelDef.faces()`: its own, `side` for the four sides, `texture`) and the particle
texture; textures are copied under `textures/<id>/` like any other. The resource pack also holds
`assets/minecraft/blockstates/note_block.json`, which `PackLayout.build` writes from the blocks'
`BlockCarriers.Plan` (the project-format skill): **every** note block state with an explicit model,
the block's own for the states blocks hold. Every texture a block model draws is listed in
`assets/minecraft/atlases/blocks.json`, and every other texture an item model draws in `atlases/items.json`, as a
`minecraft:single` source: the game's atlases only stitch `textures/block/` and `textures/item/`, so a
model texture under `textures/<id>/` that no atlas lists is the missing texture. The game appends every
resource pack's sources, so these add to vanilla's. A sprite must be in **one** atlas: 26.x warns "Duplicate sprite … already defined
in atlas" and draws it from only one, which left blocks drawing the missing texture; item models may draw from
`blocks`, as vanilla's block items do. An item look may be a block look instead of a texture
(`ItemModelDef.block`, a `@Ref(BLOCK_MODEL)`; `resource_pack.item-look` holds it to one of the two and no
`parent`): its item model is just `{ "parent": "<ns>:block/<pack>/<key>" }`, which inherits the
game's block display transforms, so it's a 3D cube in slots and the hand like a vanilla block item. A block drawn by a centity gets `<key>_hidden` (only the
particle texture), or `assets/<ns>/models/block/hidden.json` (nothing) without a look. `Packs.rebuild`
asks for the plan with `BlockCarriers.plan(snapshot.everywhere(BlockKind)...)` and `PackService` follows
`BlockKind`, so a project with blocks and no resource pack still builds one, and adding a block changes the hash.

## Equipment looks

`pack.json`'s `equipment` (`EquipmentAssetDef`: `humanoid`, `humanoidLeggings`,
`wings`, `horseBody`, `wolfBody`, each a texture) is what an item's
`equipment: { asset, slot }` (`EquipmentDef`, `RefKind.EQUIPMENT`) points at.
The runtime sets the `equippable` component when it builds a stack from the item
definition (`PaperItems.components`: `Equippable.equippable(slot).assetId(key)`,
the asset resolved in the project's namespace like `item_model`; read back
without the namespace; `PackageNames` rewrites a package's names like the
other resource pack references). Nothing is gated by version: the equipment asset lives
at `equipment/` and the component has `asset_id` from 1.21.4 on, below the
oldest supported version (1.21.11); only 1.21.2-1.21.3 used `models/equipment`.
`resource_pack.equipment-empty` (warning) is a look with no layer; a missing texture is
`resource_pack.texture-missing` like any other. A look's slot isn't checked against its
layers (leggings need `humanoidLeggings`, the rest `humanoid`): the editor's
hint says so.

## Game textures

`pack.json`'s `textures` redraws any of the game's textures (`block/stone`, `entity/zombie/zombie`,
`gui/container/inventory`): `PackLayout.build` copies the picture (and its `.mcmeta`, when the pack has
one) over `assets/minecraft/textures/<name>.png`, only for names `client/vanilla.json` lists. The game has
thousands, so the import's `index.json` lists every texture's size, but `vanilla.json` only the container
screens' and the ones the project names (`ProjectOutline.replacedTextures`, which `syncVanilla` passes to
`vanillaLooks`), plus `unknownTextures` for names the game lacks. A name in neither is "not looked up yet"
(`resource_pack.vanilla-missing`, which makes the editor sync), not unknown. Sizes: `ReplacedTexture.fit`
(screens' sheets and colour maps exact, everything else the same shape at any scale; an animated game
texture's replacement is one square frame unless it brings its own `.mcmeta`, since the game reads an
animation's `.mcmeta` from the same pack as the picture). The editor's **Game textures** section copies the
game's PNG and `.mcmeta` to `textures/vanilla/<name>.png` to start from (`useGameTextures`).

## Game models

`pack.json`'s `models` redraws the game's models (`block/red_mushroom` → a model file of the pack): `PackLayout.build`
writes `PackModels.write` of it over `assets/minecraft/models/<name>.json`, its pack textures stitched into the blocks
atlas for a `block/` model (the items atlas otherwise), only for names `client/vanilla.json`'s `models` lists. The
import extracts every model, so the editor asks for each name the outline's `replacedModels` holds
(`vanillaLooks`) and writes `models` and `unknownModels`; checks as for `textures` (`resource_pack.vanilla-missing`,
`resource_pack.replace-name`). Only drawing changes: collision, selection and which model a state draws (the
blockstate file) stay the game's.

## Reload

Any file under `resource_packs/<id>/` → the resource `resource_pack:<id>` → every resource pack is rebuilt
(they share numbering) **before** other resources in the batch, so menus
and scripts see the new characters. Reported as `resource_pack:<id>` with `pack:
PackBuild(sha1, url, bytes)` and `reattached` = players it was sent to.

## The start-up datapack

Advancement text goes through the same MiniMessage pass as everything else,
so `<glyph:ui/coin>` in a title is the resource pack's glyph character, and an icon's
`itemModel` (`ui/ruby`) becomes `minecraft:item_model: showcase:ui/ruby`. The
datapack is built before the worlds load, from files alone, so it asks the
resource packs only for what `compileResourcePacks` says from the files (no image facts the
server would need to measure).

## How to…

**Add a kind of resource pack content**: format first (`ResourcePackFile`, validator,
`CompiledResourcePack`, `PackLayout`, `docs/format/resource-pack.md`, goldens — project-format
skill), then anything the plugin must resolve (`Packs`), then the editor.

**Change the zip**: keep it deterministic; `PackTest` checks byte equality and
hash stability. A change to entry contents changes every player's download.

**Change delivery**: `Packs.send`/`sendAll`, `ResourcePackOps` in the adapter;
`PackTest` covers serving (real HTTP) and resend rules; the integration test
fetches the URL from a real server and checks the SHA-1, then rebuilds.

```sh
node tools/gradle.mjs :plugin:runtime:test --tests '*PackTest*'
pnpm test:integration
```
