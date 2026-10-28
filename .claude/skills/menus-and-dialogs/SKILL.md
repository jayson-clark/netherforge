---
name: menus-and-dialogs
description: How NetherForge runs menus and dialogs on the server - shared vs per-player windows, locked clicks, each window's copy of the menu's one script and the dialog's one script, the events they listen to (click paths, open/close, drag, press, dialog close through the exit action), item tables (ItemDef in Lua spelling - kind, script data in the stack's PDC, opaque raw), skinned titles, what reload does to open windows, dialogs built per show with customClick callbacks, typed input values, dialog_list. Read before touching apps/plugin/runtime/.../menu, .../dialog, .../item, PaperMenus, PaperDialogs, PaperItems, or the Menu/Slot/Dialog/Button Lua API.
---

# Menus and dialogs

The spec users read: `docs/format/menu.md`, `docs/format/dialog.md`;
the Lua API: `packages/api/src/spec/menu.ts` (classes `Menu`, `Slot`, `Dialog`,
`Button`, shapes `Item`, `MenuClickEvent`, …) and the `menu`/`dialog`
surfaces in `packages/api/src/spec/index.ts`. A project menu lives in
`menus/<id>/menu.json`; an open window of it is a `Menu` in Lua and a
`Menus.Window` in the runtime. (`Inventory` is kept for real inventories:
a player's, a chest's.) `menu:slot(i)` and `dialog:button(key)` are handles
keyed by the window or dialog id plus the index or key, checked when made
while the window or dialog exists, answering nil once it's gone.

| Path                                                           | Owns                                                           |
| -------------------------------------------------------------- | -------------------------------------------------------------- |
| `apps/plugin/runtime/.../menu/Menus.kt`                        | windows, their script, click dispatch, retiring, reload        |
| `apps/plugin/runtime/.../item/LuaItems.kt`                     | item tables ↔ `ItemData`, strict reading                       |
| `apps/plugin/runtime/.../dialog/Dialogs.kt`                    | one record per dialog, its script, press dispatch, reload      |
| `apps/plugin/runtime/.../platform/Platform.kt`                 | `MenuOps`, `WindowSpec`, `ItemData`, `DialogOps`, `DialogSpec` |
| `apps/plugin/runtime/.../platform/PlatformEvents.kt`           | `menuClicked/Dragged/Closed`, `dialogPressed`                  |
| `apps/plugin/paper-common/.../PaperMenus.kt`, `PaperEvents.kt` | Bukkit inventories (holder = window UUID), events              |
| `apps/plugin/paper-common/.../PaperItems.kt`                   | `ItemData` ↔ `ItemStack`                                       |
| `apps/plugin/paper-common/.../PaperDialogs.kt`                 | building Paper dialogs, the callbacks                          |

## Menus

- **A window** is to `menu.json` what an instance is to a centity. A
  `shared` menu has one, made at load (in `Menus.define`, before any script
  runs, so a module body finds it; its script starts in `start`), whose id is the menu's id
  (`nf.menus.shared("bank")` finds it); any other gets a fresh window (UUID id)
  per `player:open_menu`, retired when its last viewer closes it. Runtime
  ids are strings; the platform knows windows by a UUID the runtime picks.
- **One script, a copy per window.** `menu.json`'s `script` runs once per
  window in its own `Scope` (own globals, own budget, failures disable only
  it), with `this` the window's `Menu`. A slot has no script: the script
  listens with `this:slot(13):on("click", ...)`. The body runs before anyone
  sees the window (fill it in there) and registers its handlers. A retired
  window drops every handler on it and its slots (`scripts.dropTarget`),
  whoever registered them.
- **Clicks.** `menuClicked` → `Events.SLOT_CLICK` on the clicked slot
  (clicks in the window only) → `MENU_CLICK` on the window →
  `NF_MENU_CLICK`, one `MenuClickEvent` along the path until a handler stops
  it; the answer is whether it ended cancelled. A **locked** window starts any
  click with `movesItems` (a click in the window, a shift-click or
  double-click collect from below) already cancelled, and `event:uncancel()`
  lets one through; clicks that only rearrange the player's own bag start
  uncancelled. Drags raise `MENU_DRAG` (the window only), precancelled on a
  locked window when they cover any of its own slots (`own_indices`, the bag
  below, never precancel).
- **Outside our windows** a click or drag is `player_click_inventory` /
  `player_drag_inventory` (`PaperGameEvents.onClickInventory`/`onDragInventory`,
  skipped while one of ours is the top inventory, and on the creative screen).
  The swap-hands key over a slot is a click (`swap_offhand`), never a
  `PlayerSwapHandItemsEvent`, so `onSwapInInventory` (HIGHEST, ignoreCancelled:
  after both click listeners let it through) raises `player_swap_hands` with
  `in_inventory`. The fake bots mirror all three.
- **Context**: `player:open_menu(menu, { context = v })`
  keeps `v` (a `LuaRef`, the very value) on the new `Window`; the script reads
  it with `this:context()` (its body already can) and every `MenuEvent`,
  `MenuClickEvent` and `MenuDragEvent` of the window carries it. Released
  when the window retires. A shared menu with a context is an error
  (`PlayerImpl.openMenu`, checked before anything is kept).
- **`open`** (→ `menu_open`) is raised by the runtime when _it_ opens a window
  (it knows who), not from a server event: a retitle reopens windows and that
  mustn't look like an open. **`close`** (→ `menu_close`) comes from the
  server (Escape, logout, another window opening). Retiring waits a tick:
  while a close is handled the closer can still count as a viewer.
- **Paper sharp edges.** Opening/closing inside a click handler is unsafe, so
  `PaperMenus` defers those to the next tick while `inClick` is set.
  Retitling (a skin is part of the title) rebuilds the Bukkit inventory and
  reopens it for viewers; those closes are swallowed (`rebuilding`). Deferred
  closes check the player still has _that_ inventory open.
- **Titles.** `skinTitle(skin, shift) + title`, where the skin part is
  `<white><font:<ns>:gui>` + `CompiledResourcePack.Skin.titlePrefix(shift)` + `</font></white>`
  (white so the art isn't tinted by the container's title colour). The
  prefix is zero wide, so the words are drawn over the art from the vanilla
  title position. A dispenser, dropper or crafter centres its title on the
  words, moving the prefix with them, so `Menus.composeTitle` shifts the
  picture back by `MenuType.skinShift(width)`: the words' width from
  `TextWidth` over the project's `fonts/default.json` and the resource packs' glyph
  advances (`ProjectSession.measureText`), 0 for no words. When the words
  can't be measured exactly the shift is 0 (the skin moves with them) and
  format has already warned (`font.needed`, `DefaultFontValidator.checkCentredTitle`).
  Saving `fonts/default.json` re-renders every title. No title and no skin falls back to the file's
  `name`; with a skin, no fallback. The editor's preview must compose the same.
- **Text** anywhere (titles, names, lore, dialog text) may hold
  `<glyph:ui/coin>`; the adapter's `PaperText.mini` resolves it.

### Templates: menus made in Lua

`nf.menus.create(definition)` makes a `Menus.Template`: a
`CompiledMenu` from the definition (`api/Definitions.kt#menuFrom`: the table
in `menu.json`'s shape, `slots` read as items by index, through
`MenuKind.parse` and `MenuValidator`, the skin checked against the resource packs),
with a UUID id and no script. `player:open_menu(template)`
(`Menus.openTemplate`) makes a new window of it every time (never shared;
a context is fine). The window's `template` is the template's id: its
`kind()` is nil, `menu:template()` hands the template back, and every event
on it goes on to the template after the window (`slot → menu → template →
nf` for clicks; `menu → template → nf` for open and close; `menu →
template` for drags). A template belongs to the scope that created it
(`Menus.removeOwned`, from its `scopeReleased`) or goes with
`template:remove()`: its windows retire and its handlers are dropped.

### Items

`Item` tables are `ItemDef` in Lua spelling (`kind`, `hide_tooltip`,
`item_model`, `tooltip_style`, and the data components `max_stack_size`,
`rarity`, `attribute_modifiers` (each `{ id?, attribute, amount, operation,
slot? }`, the id defaulting to `AttributeModifierDef.defaultId`:
`<namespace>:<attribute>_<index>`), `can_break`,
`can_place_on`, `hidden_tooltip_components` (`tooltip_display`'s hidden
components: `TooltipComponent`, a component id from `TooltipComponent.ALL`, which
the schema, the editor's types, the Lua type and the docs all read; the adapter
maps each to the server's component through its registry and leaves hidden
components it doesn't own alone), `food` (also given `consumable` so it can be eaten) and
`cooldown` (`use_cooldown`), `equipment` (`equippable`: `{ asset, slot }`,
the asset a resource pack's equipment look in the project's namespace, read back
without it; a slot the format has no word for, a hand, leaves the stack to
`raw`)) plus `raw`: the whole stack, Paper-serialised
and base64, present only when rebuilding from the fields would lose something
(`PaperItems.toItem` checks with `isSimilar`). `toStack` builds _over_ `raw`
(when the item type matches), so read → change a field → write keeps a book's
pages. `data` is the script's own data (`ItemDef.data`, JSON values by key;
the opaque carry-through is `raw`): `PaperItems` keeps it as JSON text under `netherforge:data`
in the stack's persistent data container, so it goes wherever the item goes
and is part of the lossless check; `data = {}` from Lua clears it, leaving it
out leaves it alone. It holds what a `data()` table may (`Vec3`s,
`Location`s, `Entity` (a `Player` too) and `Centity` handles): `RuntimeApi.item` reads the
item table as the one Lua↔JSON codec would (so `ItemDef.data` is tagged JSON,
easy to compare as JSON for item matching), but in Kotlin (`LuaHost.fieldsJson`),
because the codec is Lua and its instructions are the script's: plain fields cost
nothing, and plain `data` is written as the codec's very text (keys in Lua's `<`
order, asked of Lua, floats spelled as it spells them, `CodecNumbers`); only typed
values and odd keys fall back to the codec
(`binding/ApiCostTest`'s item tables), and `LuaItems.write` hands it
back as that JSON, which `pushValue` turns back into typed values. An item
match (`ItemMatch`, a partial item) is read the same way. Reading is strict
(`LuaItems.read`): unknown field, wrong type, something in `data` that can't
be saved (named by key path: `item.data.f: a function can't be saved`),
unknown item (`Rules.item` with live game data) or a resource pack reference the
project doesn't have is an error at the script's line. Counts come back
filled in.

### Project items (`items/<id>/item.json`)

The spec users read is `docs/format/item.md`; the Lua side is
`packages/api/src/spec/items.ts` (`ProjectItem`, `nf.items`, the `item`
surface).

- **The file** (`ItemFile`) is an item's look: `ItemDef`'s fields minus the
  stack's own (`count`, `damage`, `data`, `item`; `ItemFile.STACK_FIELDS`,
  and `ProjectItemsTest` holds the two field lists together), plus `script`.
  `look()` is it as an `ItemDef`.
- **Naming one**: `ItemDef.item`, a `ResourceRef` (`ruby`, or a package's
  `acme:ruby`). Its `kind` may then be left out (`""` until resolved);
  `Rules.item` allows that, `Projects.load` checks the item exists
  (`reference.item`, like every reference) and `ItemKind.checkStack` that a
  kind given too matches (`item.kind-mismatch`, pointing at the item's
  `$.kind` too), and `LuaItems.read` does the same against the runtime's
  items (`Items.idOf` resolves a reference to one of the project's ids).
  A script's item table names things in its own package's terms both ways
  (`PackageNames.item` in, `spell(item, to)` out, and the table remembers
  which package it was spelled for: plugin-runtime, "Packages in a
  session"); `LuaItems` only ever sees names as the project names them.
- **Resolving and stamping is the platform's**, so every path (Lua tables,
  menu slots, dialog bodies, particle items, recipe results) gets it for
  free: `Platform.bind` hands the adapter `Items.look` (`ItemLook`: the look
  and its hash, `ProjectItems.hash`), and `PaperItems.toStack` resolves
  (`ProjectItems.resolve`: the stack's own fields win) and writes the item's
  **namespaced** id (`shop:ruby`, the project's namespace from
  `Platform.bind`) under `netherforge:item`, and the look's hash under
  `netherforge:item_look`, into the PDC; reading a stack gives the id back as
  a file writes it (`ruby`; `PaperItems.reference`). Built over a
  `raw` already stamped for the same item, the stamp is kept, so a stale
  stack read and written back by a script stays stale. `toItem` reads the id
  into `ItemDef.item` and the hash into `ItemData.look`. The fake does the
  same in `FakePlatform.stack`.
- **Refreshing**: `ProjectItemOps.refresh(inventory)` rewrites stale stacks
  (`PaperItems.restyled`: only the stamp is read for a current stack; a stale
  one is rebuilt with `ProjectItems.restyle`, keeping count, damage, script
  data and, when the look sets none, enchantments, and then the old PDC is
  copied over without replacing, so other plugins' data stays). The runtime
  (`item/Items.kt`) calls it on join, on `playerOpenInventory`, the tick after
  a pickup, and for every online player when the item reloads.
- **The script**: one per item, like a dialog's (`ScopeOwner.ItemScript`,
  `this` the `ProjectItem`). Events are the player events with the item's
  stage first on the same path (`ServerEvents.itemEvent`,
  `Items.stage`): `use`, `interact`, `consume`, `drop`, `pickup` from the
  event's own item; `hit`, `break_block`, `interact_entity` from what's held
  (`Items.heldStage`, looked up only while some item listens, through
  `Scripts.watch`).

- **Views** (`view` on the item, `item_view` on `nf`, `ItemViewEvent`): how a stack looks to the one player
  it's sent to, never stored. The adapter asks for every stack it sends a player's game:
  `PlatformEvents.itemViewing(reference)` first (two map lookups while nobody listens; `Items.viewing`), then
  `itemViewed(player, stack)` → `ItemView(name, lore)` or null (unchanged, or a handler failed: `Items.view`
  compares `Scripts.handlerFailures` around the emit). `player:refresh_items()` only queues
  (`Items.refreshView`); `Items.tick(UPKEEP)` calls `ItemViewOps.refresh` once per player, and for everyone
  when any view handler is added or goes (`Scripts.counting`, which hears a reload's drop-and-re-add that
  `watch` can't) or a session starts. Deferred on purpose: a `close` handler runs while the server still
  has the closing window as `containerMenu`, and slot packets for it would be dropped by the game.
  **Paper** (`PaperItemViews`, paper-internals `ItemViews`): every window sends through the player's
  `ContainerSynchronizer`, which each window takes at `initMenu`, so `install` (on join, LOWEST) swaps the
  player's final `containerSynchronizer` field by reflection and re-sets the open windows' (which resends
  them). Ours views each stack and hands it to the server's own one; its `RemoteSlot`s (`ViewSlot`, a
  `RemoteSlot.Synchronized` so `transferState` still copies them) keep the real stack, so a look never
  causes a resend, plus the look last sent (`shown`, which `refresh` diffs against, so a refresh sends only
  what changed) and match a click's hash of the look sent for a stack as that stack (no resend per click).
  Looks sent are remembered per player (512, both ways), which is also how `InventoryCreativeEvent` (LOWEST)
  maps a stack the creative client echoes back to the real one. Survival clicks only carry hashes; anvil
  renames are typed text. Stacks the server sends past the synchronizer (crafting result, pick-block,
  Bukkit's direct `setItem` packet) go as they are until that slot next syncs. The fake (`FakeItemViews`)
  sends at the end of a tick (`tickWorld`) what changed, keeps what each place was shown (`nf.test.shown`,
  fake bots' screens) and maps creative stacks back the same way.

### Recipes (`recipes/<id>.json`)

The spec users read is `docs/format/recipe.md`; the Lua side is
`packages/api/src/spec/recipes.ts` (`nf.recipes`, `player:discover_recipe`
and friends).

- **Format**: `RecipeFile`, one flat shape for every type; `RecipeValidator`
  says which fields each type takes. `Ingredient` is a union (an id string,
  `#tag`, or `{ "item": id }`) with its own serializer, which the contract
  generator special-cases by `Ingredient.SERIAL_NAME`. Recipes are the one
  kind that's a file rather than a folder: `RecipeKind`'s layout is
  `Layout.SingleFile(".json")` (the project-format skill's registry), which
  `Kinds.classify`, the loader and the editor's `KINDS` table all follow.
- **Runtime** (`item/Recipes.kt`): the files' recipes and those scripts
  register (each owned by its scope, removed in its service's `scopeReleased`
  listener; `api/Definitions.kt#recipeFrom` holds a script's table to the
  file's rules), pushed through `RecipeOps.add`/`remove` at once and sent to
  players once a tick (`resend`). An item's reload rebuilds the recipes naming
  it.
- **Matching a project item is the adapter's** (`PaperRecipes`): it
  registers a project-item ingredient as a `MaterialChoice` of the item's
  kind, and at `LOWEST` priority (before `PaperGameEvents` and scripts)
  checks every place of the matched recipe by the stacks' `netherforge:item`
  stamps (against the ingredient's reference resolved in the namespace)
  stamps: `PrepareItemCraftEvent` (shaped, normal or mirrored, after trimming
  the pattern as the server does; shapeless as a bipartite matching),
  `CrafterCraftEvent`, `BlockCookEvent` (which `FurnaceSmeltEvent` shares),
  `PrepareSmithingEvent` and `PlayerStonecutterRecipeSelectEvent`. A vanilla
  kind or tag never takes a project item, in any recipe. The project's
  recipes are keyed in its namespace (`shop:ruby_sword`); recipe ids in
  events are bare for the project's (`PaperRecipes.idOf`).

### Reload (`menus/<id>/…`)

`Menus.reload`, reported as `menu:<id>` with `reattached` = windows kept:

- Same container type and size: the **window is kept** (viewers stay open),
  contents kept **except slots whose authored item changed in the file**,
  which take the new item; title/skin/lock follow the file only where the file
  changed them; the script restarts (it unloads, then its body runs again); handlers other scripts put on the window stay. So a shared shop
  keeps its stock and an edit to a slot shows at once.
- Shape changed: rebuilt from the file and reopened for its viewers.
- Shared → not shared: the shared window closes. Deleted: every window closes.
- Errors: the last good definition keeps running (`ok: false`).
- Nothing is persisted: a restart rebuilds every window from its file.

## Dialogs

- **One of each, no instances.** A dialog holds nothing, so its one script
  (`script` in `dialog.json`, `this` the `Dialog`, with the files beside it
  that it requires) starts at load and runs until reload. Buttons have no script: it listens with
  `this:button("done"):on("press", ...)`. `show` builds it **fresh every
  time** (`DialogSpec`) and hands it to `Player#showDialog`; nothing is
  registered. Handlers on a dialog survive its reloads (only its own script
  restarts); they go when the dialog is deleted, and a button's when a reload
  drops it. Body elements may have a `key`, which an opening's `body` option names.
- **Openings**: `open_dialog`, `open_for` and `ask`
  take `DialogOpenOptions`. `Dialogs.opened` checks `values` (by input key,
  the right kind for the input: text, boolean, an option id, a number in
  range), `body` (by body element key) and `title` against the file and
  returns the file as this opening draws it (a copy; nothing registered, the
  platform builds it like any file). `context` (a `LuaRef`) is kept in the
  player's `Opening` and handed to every press and the `close` of that
  opening (a dialog a `dialog_list` led to counts as the same opening);
  released when the opening ends (a press that closes it, after the press is
  heard; a close; a new dialog over it; a quit; the Lua state stopping). A
  mistake in the options is checked before the player is looked up, so it's
  an error even for someone offline.
- **Buttons are callbacks**: `DialogAction.customClick(callback, options)`
  with unlimited uses and a day's lifetime. Never a command template. Presses
  hop to the main thread if they arrive elsewhere.
- **Values** (`event.values`, by input key): text and option id as strings, a
  checkbox as its `onTrue`/`onFalse` string, a slider as a number. Built from
  the _file's_ inputs, so the keys are exactly the declared ones.
- **Dispatch**: `BUTTON_PRESS` on the button → `DIALOG_PRESS` on the dialog →
  `NF_DIALOG_PRESS`, one `DialogPressEvent` (`target` the button, `key` its
  key) until a handler stops it. A press for a dialog or button that no longer
  exists (reloaded since it was shown) is logged and ignored.
- **`dialog_list`** builds its listed dialogs inline
  (`RegistrySet.valueSet`), each with its own callbacks, guarding cycles.
- **`close`**: leaving is reported only through a dialog's
  exit action, so `PaperDialogs` gives every dialog an exit button whose
  callback is `PlatformEvents.dialogClosed`: a `multi_action` dialog a "Back"
  one (`gui.back`), a `dialog_list` its own button with that callback (so it's
  never a `press`), and a notice or confirmation one in place of a missing
  button (`gui.ok`, `gui.yes`, `gui.no`). Escape on a notice is a press of its
  button, on a confirmation of its second, when the file has them. `Dialogs`
  keeps which project dialog each player has open (set on show, cleared by a
  press unless `afterAction` keeps the screen up) and raises `close` itself for
  closes the server causes: `player:close_dialog()` and another dialog shown
  over it. A disconnect raises nothing (`player_quit` covers it; `forget`).
- **On the pause screen and the quick actions key** (`pauseMenu`,
  `quickActions` in `dialog.json`, `DialogJson`). Those places read the
  server's dialog registry, filled only as it loads, so the dialog is written
  into the start-up datapack (`DialogKind.datapackAll`: a dialog file for each
  flagged dialog and the ones a flagged `dialog_list` lists, plus the
  `minecraft:pause_screen_additions` / `quick_actions` tags) and a change
  restarts the dev server (the datapack check, hot-reload skill). A registry
  dialog is static, so its buttons are `minecraft:dynamic/custom` actions with
  ids `<ns>:dialog/<dialog>/press/<index>` and `.../exit`
  (`DialogJson.pressId/exitId/click`); the game answers one with a custom click,
  which Paper raises as `PlayerCustomClickEvent`. `PaperDialogs.customClicked`
  hands it to `PlatformEvents.customClicked(player, id, DialogAnswers)` and
  `Dialogs.registryClicked` turns it into the same `pressed(dialog, button.key,
values)` / `closed` a callback gives, so the dialog's one script can't tell
  them apart. Only a dialog in the registry set is heard, since the game takes
  a custom click from a player at any time (forging a press of a dialog `show`
  alone could open is ignored). Index, not key, because a resource location
  can't hold the uppercase a key may; a button edited since start is out of date
  anyway (a restart is pending). No context (nothing in Lua opened it), and the
  opening options don't apply. Constraints the real game enforces and the
  writer follows: `pause: false` always (a pausing dialog that stays up is
  refused, and a bad dialog fails the whole server's start), a `multi_action`
  needs at least one action (an empty one gets its exit button). Lua-made
  dialogs reject `pause_menu`/`quick_actions`. Available on every supported
  version (dialogs are 1.21.6+).
- **Made in Lua** (`nf.dialogs.create`): a `Record` with an
  `owner` scope, a UUID id and no script, from `api/Definitions.kt#dialogFrom`
  (snake_case keys to the file's camelCase, item bodies' items read as `Item`
  tables, then `DialogKind.parse` and `DialogValidator`; a `dialog_list` may
  name any dialog the runtime has). Showing, presses, `close` and `ask` are
  the same as a file's. `Dialogs.removeOwned` drops it (and its handlers)
  when its scope stops; `ids()` lists only the project's.
- **`dialog:ask(player, options?)`** (in a task) is hand-written in the prelude: it
  checks `options` against the `DialogOpenOptions` shape (`check_shape`), then
  subscribes the task to the dialog's `press` and `close` and to
  `player_quit`, each filtered to that player, then opens the dialog and
  waits. The press is the answer; a close or quit is `nil`. It hears the press
  at the dialog stage, so a button handler that stops it hides it from `ask`.

## How to…

**Real inventories** (`Inventory`, `api/InventoryImpl.kt`) are not windows:
an `InventoryRef` the platform finds again on every call, its handle's key a
string (`player/<uuid>`, `block/<x>/<y>/<z>/<world>`, …). Their item methods
mirror `Menu`'s; taking, counting and finding go through `item/ItemMatch.kt`
(`ItemMatch`: a kind, or a partial item read with `LuaItems.read(partial =
true)`, fields compared as `LuaItems.write` spells them, `data` as a subset of
the stack's tagged JSON) and `ItemSlots`, which work over any row of slots, so
a menu can share them.

**Add a field to items**: `ItemDef` (format, project-format skill), then
`LuaItems` (read + write + `FIELDS`), `PaperItems` (both ways: a data
component is written in `components` and read back only when
`isDataOverridden`, so a kind's own defaults never show as fields), the `Item`
shape in `packages/api/`, the editor's `ItemEditor`, a runtime test
(`MenuTest`) and the integration scenario's components module.

**Add a menu event**: `packages/api/` (`menuEvents` in `spec/menu.ts`, the
payload in `menuShapes`) → `pnpm generate` (`Events.MENU_X`, its payload
class) → raise it from `Menus` with `scripts.emit` → test.

**Add a `Menu` (or `Slot`, `Dialog`, `Button`) method**: spec, `pnpm generate`,
implement the new `MenuApi` method in `api/MenuImpl.kt`, a test in
`MenuTest` (or `DialogTest`).

**Change click semantics**: `Menus.click` (and `movesItems` in
`PaperEvents`), plus `MenuTest`.

```sh
node tools/gradle.mjs :plugin:runtime:test --tests '*MenuTest*' --tests '*DialogTest*'
```
