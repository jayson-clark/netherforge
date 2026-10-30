<!-- Generated from format's ProblemCodes by its golden test (UPDATE_GOLDEN=1 pnpm test). Do not edit. -->

# Problems

Every problem NetherForge reports has a stable code: the editor shows it beside the message, and `netherforge check` prints it. An error stops the resource from running (the server keeps its last good version); a warning doesn't. Each problem points at a file, and at the value in it when it's about one; a broken reference also points at its other end.

## Reading files

### `parse` {#parse}

Error. The file isn't JSON, or doesn't have the shape its kind needs (an unknown key, a missing one, a wrong type). See [the project format](project.md).

## The project

### `project.no-manifest` {#project-no-manifest}

Error. The folder has no `netherforge.json`, so it isn't a project. See [the project format](project.md).

### `project.format-version` {#project-format-version}

Error. The project is in a format version other than the one this NetherForge reads. See [the project format](project.md).

### `project.namespace` {#project-namespace}

Error. `namespace` isn't a usable namespace: lowercase letters, digits and `_`. See [the project format](project.md).

### `project.namespace-reserved` {#project-namespace-reserved}

Error. `namespace` is one the game, a server or NetherForge itself uses. See [the project format](project.md).

### `project.version` {#project-version}

Error. `version` isn't a semantic version like `1.2.0`. See [the project format](project.md).

### `project.minecraft` {#project-minecraft}

Error. `minecraft` isn't a Minecraft version. See [the project format](project.md).

### `project.minecraft-old` {#project-minecraft-old}

Error. `minecraft` is older than the oldest version NetherForge supports. See [the project format](project.md).

### `project.feature` {#project-feature}

Error. Something the project uses arrived in a newer Minecraft than `minecraft` names. See [the project format](project.md).

### `project.name` {#project-name}

Warning. The project has no name. See [the project format](project.md).

### `project.world-name` {#project-world-name}

Error. A name in `managedWorlds` or `worlds` isn't a world name. See [the project format](project.md).

### `project.world-rule` {#project-world-rule}

Error. A world's `gameRules` names no rule id, or gives one a value that isn't true, false or a whole number. See [the project format](project.md).

### `project.world-spawn` {#project-world-spawn}

Error. A world's spawn limit or interval in `worlds` is below 0. See [the project format](project.md).

### `project.world-height` {#project-world-height}

Error. A world in `worlds` names a terrain and a dimension, and a height the terrain names is outside that dimension's build limits. See [format/worlds.md](worlds.md).

### `project.permission-node` {#project-permission-node}

Error. A node in `allow.permissions` isn't a permission node. See [the project format](project.md).

### `project.requires-host` {#project-requires-host}

Error. A host in `requires.http` isn't a host name, or `*.` and one. See [the project format](project.md).

### `project.requires-plugin` {#project-requires-plugin}

Error. A name in `requires.plugins` isn't a plugin's name in lowercase. See [the project format](project.md).

### `project.setting-name` {#project-setting-name}

Error. A setting's name in `settings` isn't an id. See [format/settings.md](settings.md).

### `project.setting-description` {#project-setting-description}

Warning. A setting has no description for whoever runs the project to read. See [format/settings.md](settings.md).

### `project.setting-range` {#project-setting-range}

Error. A setting's `min` is more than its `max`. See [format/settings.md](settings.md).

### `project.setting-choices` {#project-setting-choices}

Error. A choice setting offers no choices, an empty one, or one twice. See [format/settings.md](settings.md).

### `project.setting-default` {#project-setting-default}

Error. A setting's `default` isn't one of its own values. See [format/settings.md](settings.md).

### `project.id` {#project-id}

Error. A resource's folder or file name isn't an id, so it's ignored. See [the project format](project.md).

### `project.group` {#project-group}

Error. A group folder's name isn't an id in parentheses, so everything in it is ignored. See [the project format](project.md).

### `project.duplicate-id` {#project-duplicate-id}

Error. Two resources of one kind have the same id in different group folders; only the first is read. See [the project format](project.md).

### `project.missing-file` {#project-missing-file}

Warning. A resource folder has no main file, so it's ignored. See [the project format](project.md).

### `project.stray-file` {#project-stray-file}

Warning. A file sits where no resource of the folder's kind can be, so it's ignored. See [the project format](project.md).

## The default font

### `font.minecraft` {#font-minecraft}

Error. `fonts/default.json`'s `minecraft` isn't a Minecraft version. See [the project format](project.md).

### `font.code-point` {#font-code-point}

Error. A key in `fonts/default.json`'s `advances` isn't a code point. See [the project format](project.md).

### `font.advance` {#font-advance}

Error. An advance in `fonts/default.json` is negative. See [the project format](project.md).

### `font.stale` {#font-stale}

Warning. `fonts/default.json` holds another Minecraft version's advances; the editor rewrites it once that version's client is imported. See [the project format](project.md).

### `font.needed` {#font-needed}

Warning. Something has to measure text on the server, but `fonts/default.json` is missing or stale. See [the project format](project.md).

## References

### `reference.syntax` {#reference-syntax}

Error. A reference isn't written as `id` (in the project's own namespace) or `namespace:id`, in the shape its kind takes. See [references](references.md).

### `reference.namespace` {#reference-namespace}

Error. A reference names a namespace that's neither the project's own nor a package's. See [references](references.md).

### `reference.item` {#reference-item}

Error. A reference names a project item that doesn't exist. See [references](references.md).

### `reference.dialog` {#reference-dialog}

Error. A reference names a dialog that doesn't exist. See [references](references.md).

### `reference.loot-table` {#reference-loot-table}

Error. A reference names a loot table that doesn't exist. See [references](references.md).

### `reference.buff` {#reference-buff}

Error. A reference names a buff that doesn't exist. See [references](references.md).

### `reference.advancement` {#reference-advancement}

Error. A reference names an advancement that doesn't exist. See [references](references.md).

### `reference.block` {#reference-block}

Error. A reference names a project block that doesn't exist. See [references](references.md).

### `reference.centity` {#reference-centity}

Error. A reference names a centity that doesn't exist. See [references](references.md).

### `reference.terrain` {#reference-terrain}

Error. A reference names a terrain that doesn't exist. See [references](references.md).

### `reference.structure` {#reference-structure}

Error. A reference names a project structure that doesn't exist. See [references](references.md).

### `reference.biome` {#reference-biome}

Error. A reference names a project biome, or a biome of the project's datapacks, that doesn't exist. See [references](references.md).

### `reference.placed-feature` {#reference-placed-feature}

Error. A reference names a placed feature the project's datapacks don't define. See [references](references.md).

### `reference.dimension-type` {#reference-dimension-type}

Error. A reference names a dimension type that doesn't exist. See [references](references.md).

### `reference.video` {#reference-video}

Error. A reference names a video that doesn't exist. See [references](references.md).

### `reference.resource-pack` {#reference-resource-pack}

Error. A reference to a skin, glyph, item model, tooltip, block model or sound names a resource pack that doesn't exist. See [references](references.md).

### `reference.resource-pack-key` {#reference-resource-pack-key}

Error. A reference names a skin, glyph, item model, tooltip, block model or sound its resource pack doesn't define. See [references](references.md).

### `reference.not-exported` {#reference-not-exported}

Error. A reference names something of a package's that the package doesn't export. See [references](references.md).

## Packages

### `package.name` {#package-name}

Error. A key in `dependencies` isn't a usable namespace, or is the project's own or a reserved one. See [packages](packages.md).

### `package.path` {#package-path}

Error. A dependency's `path` isn't a relative folder path with `/` between its parts. See [packages](packages.md).

### `package.source` {#package-source}

Error. A dependency doesn't name exactly one source (`path`, or `git` with an optional `rev`). See [packages](packages.md).

### `package.git-url` {#package-git-url}

Error. A dependency's `git` isn't a repository URL NetherForge fetches from (`https://`, `ssh://`, `user@host:path` or `file://`). See [packages](packages.md).

### `package.git-rev` {#package-git-rev}

Error. A dependency's `rev` isn't the name of a branch, a tag or a commit. See [packages](packages.md).

### `package.git-path` {#package-git-path}

Error. A package from git depends on a folder by `path`, which isn't there for anyone who fetches it. See [packages](packages.md).

### `package.git` {#package-git}

Error. A package couldn't be fetched from its git repository. See [packages](packages.md).

### `package.hash` {#package-hash}

Error. A package from git isn't what `netherforge.lock` pins for its commit (its files hash to something else), so it isn't loaded. See [packages](packages.md).

### `package.missing` {#package-missing}

Error. There's no project (no readable `netherforge.json`) where a dependency points. See [packages](packages.md).

### `package.namespace` {#package-namespace}

Error. The project a dependency points at has another namespace than the dependency's name. See [packages](packages.md).

### `package.conflict` {#package-conflict}

Error. Two packages in the dependency tree have one namespace: there can be only one of each. See [packages](packages.md).

### `package.cycle` {#package-cycle}

Error. Packages depend on each other in a loop. See [packages](packages.md).

### `package.export-kind` {#package-export-kind}

Error. A key in `exports` isn't the folder of a kind of resource. See [packages](packages.md).

### `package.export-missing` {#package-export-missing}

Error. `exports` names a resource the project doesn't have. See [packages](packages.md).

## The lock file

### `lock.missing` {#lock-missing}

Warning. The project has dependencies but no `netherforge.lock`; the editor or `netherforge lock` writes it. See [packages](packages.md).

### `lock.stale` {#lock-stale}

Warning. `netherforge.lock` doesn't say what the dependencies resolve to now; the editor or `netherforge lock` rewrites it. See [packages](packages.md).

## Scripts

### `script.path` {#script-path}

Error. `script.file` isn't a `.lua` file inside the resource's folder. See [the project format](project.md).

### `script.missing` {#script-missing}

Error. The file `script.file` names doesn't exist. See [the project format](project.md).

### `script.budget` {#script-budget}

Warning. `script.budget` is below the least a script gets, so it's raised. See [the project format](project.md).

### `script.file-name` {#script-file-name}

Warning. A Lua file beside a script is named so `require` can't reach it. See [the project format](project.md).

### `script.error` {#script-error}

Error. A script raised an error. See [the dev loop](../guide/dev-loop.md).

### `script.slow` {#script-slow}

Warning. A script takes more of a tick than the server's warning threshold. See [the dev loop](../guide/dev-loop.md).

## Modules

### `module.file-name` {#module-file-name}

Error. A module's Lua file is named so `require` can't reach it. See [the project format](project.md).

### `module.empty` {#module-empty}

Warning. A module has no `.lua` files. See [the project format](project.md).

### `module.no-init` {#module-no-init}

Warning. A module has no `init.lua` and no script requires it, so it never runs. See [the project format](project.md).

## Centities

### `centity.no-nodes` {#centity-no-nodes}

Error. A centity has no nodes. See [centities](centity.md).

### `centity.node-name` {#centity-node-name}

Error. A node's name isn't usable. See [centities](centity.md).

### `centity.unknown-parent` {#centity-unknown-parent}

Error. A node's `parent` names no node. See [centities](centity.md).

### `centity.self-parent` {#centity-self-parent}

Error. A node is its own parent. See [centities](centity.md).

### `centity.cycle` {#centity-cycle}

Error. Nodes are each other's parents in a loop. See [centities](centity.md).

### `centity.block-state` {#centity-block-state}

Error. A block display's `block` isn't a block state. See [centities](centity.md).

### `centity.unknown-block` {#centity-unknown-block}

Error. A block display names a block the target version doesn't have. See [centities](centity.md).

### `centity.block-property` {#centity-block-property}

Error. A block display's block state has a property, or a value, its block doesn't. See [centities](centity.md).

### `centity.item-id` {#centity-item-id}

Error. An item display's `item` isn't an item id. See [centities](centity.md).

### `centity.unknown-item` {#centity-unknown-item}

Error. An item display names an item the target version doesn't have. See [centities](centity.md).

### `centity.line-width` {#centity-line-width}

Error. A text display's `lineWidth` isn't positive. See [centities](centity.md).

### `centity.background` {#centity-background}

Error. A text display's `background` isn't `#AARRGGBB`. See [centities](centity.md).

### `centity.color` {#centity-color}

Error. A rect display's `color` isn't `#RRGGBB` or `#AARRGGBB`. See [centities](centity.md).

### `centity.display-size` {#centity-display-size}

Error. A rect or image display's `size` isn't two lengths more than 0. See [centities](centity.md).

### `centity.image-size` {#centity-image-size}

Error. An image display's `width` and `height` are more pixels than it can draw. See [centities](centity.md).

### `centity.hitbox-both` {#centity-hitbox-both}

Error. A hitbox has both `boxes` and `shape: "collision"`. See [centities](centity.md).

### `centity.hitbox-collision` {#centity-hitbox-collision}

Error. `shape: "collision"` is on a node without a block display. See [centities](centity.md).

### `centity.hitbox-size` {#centity-hitbox-size}

Error. A hitbox box isn't larger at `max` than at `min` on every axis. See [centities](centity.md).

### `centity.hitbox-empty` {#centity-hitbox-empty}

Warning. A hitbox has no boxes, so nothing can click it. See [centities](centity.md).

### `centity.hitbox-stale` {#centity-hitbox-stale}

Warning. A hitbox was fitted to a display that has changed since. See [centities](centity.md).

### `centity.animation-name` {#centity-animation-name}

Error. An animation's name isn't usable. See [centities](centity.md).

### `centity.animation-node` {#centity-animation-node}

Error. An animation drives a node that doesn't exist. See [centities](centity.md).

### `centity.animation-empty` {#centity-animation-empty}

Warning. An animation has no tracks. See [centities](centity.md).

### `centity.animation-length` {#centity-animation-length}

Error. An animation's `length` isn't positive. See [centities](centity.md).

### `centity.keys-past-length` {#centity-keys-past-length}

Warning. An animation has keyframes after its `length`, which never play. See [centities](centity.md).

### `centity.track-empty` {#centity-track-empty}

Warning. An animation track has no keyframes. See [centities](centity.md).

### `centity.key-time` {#centity-key-time}

Error. A keyframe's time is before 0. See [centities](centity.md).

### `centity.physics-mass` {#centity-physics-mass}

Error. A body's `mass` isn't positive. See [centities](centity.md).

### `centity.physics-collider` {#centity-physics-collider}

Error. A collider isn't larger at `max` than at `min` on every axis. See [centities](centity.md).

### `centity.physics-range` {#centity-physics-range}

Warning. A physics value outside 0 to 1 is clamped. See [centities](centity.md).

### `centity.physics-stuck` {#centity-physics-stuck}

Warning. A body's `maxSpeed` is 0, so it never moves. See [centities](centity.md).

### `centity.physics-falls` {#centity-physics-falls}

Warning. A body collides with nothing, so nothing stops it falling. See [centities](centity.md).

### `centity.health` {#centity-health}

Error. A centity's `health` has a value it can't have. See [centities](centity.md).

### `centity.rope` {#centity-rope}

Error. A rope node's `links` or `linkLength` is out of range, or it has no display, or has a hitbox or physics. See [centities](centity.md).

### `centity.body-size` {#centity-body-size}

Error. A `body`'s `width` or `height` isn't positive, or is too big. See [centities](centity.md).

### `centity.body-range` {#centity-body-range}

Warning. A `body` value out of its range is clamped. See [centities](centity.md).

### `centity.spawning-world` {#centity-spawning-world}

Error. A world in `spawning.worlds` isn't a usable world name. See [centities](centity.md).

### `centity.spawning-biome` {#centity-spawning-biome}

Error. A biome or biome tag in `spawning.biomes` isn't one the target version has. See [centities](centity.md).

### `centity.spawning-block` {#centity-spawning-block}

Error. A block or block tag in `spawning.blocks` isn't one the target version has. See [centities](centity.md).

### `centity.spawning-range` {#centity-spawning-range}

Error. A `spawning` range has its `min` above its `max`, or a light level outside 0 to 15. See [centities](centity.md).

### `centity.spawning-number` {#centity-spawning-number}

Error. `spawning`'s `weight`, `cap`, `group` or `despawnDistance` isn't positive. See [centities](centity.md).

### `centity.spawning-time` {#centity-spawning-time}

Error. A tick of the day in `spawning.time` is outside 0 to 23999. See [centities](centity.md).

### `centity.spawning-when` {#centity-spawning-when}

Error. `spawning.when` isn't a condition's name: an id, or `ns:id` for a package's. See [centities](centity.md).

### `centity.spawning-despawn` {#centity-spawning-despawn}

Warning. `spawning.despawnDistance` is within the spawner's reach, so a natural centity may go as it appears. See [centities](centity.md).

## buff

### `buff.color` {#buff-color}

Error. A buff's `color` isn't `#rrggbb`. See [format/buff.md](buff.md).

### `buff.stat` {#buff-stat}

Error. A buff's `stats` has a name that isn't one, or a value that isn't a number. See [format/buff.md](buff.md).

### `buff.ticks` {#buff-ticks}

Error. A buff's `maxTicks` is below 1. See [format/buff.md](buff.md).

## Menus

### `menu.rows` {#menu-rows}

Error. A chest's `rows` is outside 1 to 6. See [menus](menu.md).

### `menu.rows-fixed` {#menu-rows-fixed}

Error. `rows` is set on a menu type whose size is fixed. See [menus](menu.md).

### `menu.slot-key` {#menu-slot-key}

Error. A key in `slots` isn't a slot index. See [menus](menu.md).

### `menu.slot-range` {#menu-slot-range}

Error. A slot index is past the menu's last slot. See [menus](menu.md).

### `menu.slot-empty` {#menu-slot-empty}

Warning. A slot entry has no item. See [menus](menu.md).

## Dialogs

### `dialog.button-count` {#dialog-button-count}

Error. The dialog has more buttons than its type shows. See [dialogs](dialog.md).

### `dialog.confirmation` {#dialog-confirmation}

Warning. A confirmation dialog hasn't both a yes and a no button. See [dialogs](dialog.md).

### `dialog.columns` {#dialog-columns}

Error. `columns` is outside 1 to 8. See [dialogs](dialog.md).

### `dialog.columns-type` {#dialog-columns-type}

Error. `columns` is set on a dialog that isn't `multi_action`. See [dialogs](dialog.md).

### `dialog.list-type` {#dialog-list-type}

Error. `dialogs` is set on a dialog that isn't a `dialog_list`. See [dialogs](dialog.md).

### `dialog.list-empty` {#dialog-list-empty}

Warning. A `dialog_list` lists no dialogs. See [dialogs](dialog.md).

### `dialog.body-key` {#dialog-body-key}

Error. A body element's `key` isn't usable. See [dialogs](dialog.md).

### `dialog.body-duplicate` {#dialog-body-duplicate}

Error. Two body elements have the same `key`. See [dialogs](dialog.md).

### `dialog.body-empty` {#dialog-body-empty}

Warning. A message has no text. See [dialogs](dialog.md).

### `dialog.input-key` {#dialog-input-key}

Error. An input's `key` isn't usable. See [dialogs](dialog.md).

### `dialog.input-duplicate` {#dialog-input-duplicate}

Error. Two inputs have the same `key`. See [dialogs](dialog.md).

### `dialog.button-key` {#dialog-button-key}

Error. A button's `key` isn't usable. See [dialogs](dialog.md).

### `dialog.button-duplicate` {#dialog-button-duplicate}

Error. Two buttons have the same `key`. See [dialogs](dialog.md).

### `dialog.max-length` {#dialog-max-length}

Error. A text input's `maxLength` is below 1. See [dialogs](dialog.md).

### `dialog.lines` {#dialog-lines}

Error. A text input's `lines` is below 1. See [dialogs](dialog.md).

### `dialog.options-empty` {#dialog-options-empty}

Error. A `single_option` input has no options. See [dialogs](dialog.md).

### `dialog.option-duplicate` {#dialog-option-duplicate}

Error. Two options of one input have the same `id`. See [dialogs](dialog.md).

### `dialog.option-initial` {#dialog-option-initial}

Warning. More than one option starts selected; the first wins. See [dialogs](dialog.md).

### `dialog.range` {#dialog-range}

Error. A `number_range` input's `end` isn't above its `start`. See [dialogs](dialog.md).

### `dialog.range-step` {#dialog-range-step}

Error. A `number_range` input's `step` isn't positive. See [dialogs](dialog.md).

### `dialog.range-initial` {#dialog-range-initial}

Error. A `number_range` input's `initial` is outside its range. See [dialogs](dialog.md).

## Items

### `item.kind` {#item-kind}

Error. An item has no `kind` (and names no project item), or its `kind` isn't an item id. See [items](item.md).

### `item.unknown` {#item-unknown}

Error. An item's `kind` is an item the target version doesn't have. See [items](item.md).

### `item.kind-mismatch` {#item-kind-mismatch}

Error. A stack of a project item gives a `kind` other than the item's. See [items](item.md).

### `item.count` {#item-count}

Error. `count` is outside 1 to 99, or above `maxStackSize`. See [items](item.md).

### `item.damage` {#item-damage}

Error. `damage` is negative. See [items](item.md).

### `item.map` {#item-map}

Error. `map` is negative. See [items](item.md).

### `item.enchantment` {#item-enchantment}

Error. A key in `enchantments` isn't an enchantment id. See [items](item.md).

### `item.unknown-enchantment` {#item-unknown-enchantment}

Error. An enchantment the target version doesn't have. See [items](item.md).

### `item.enchantment-level` {#item-enchantment-level}

Error. An enchantment level is outside 1 to 255. See [items](item.md).

### `item.color` {#item-color}

Error. `color` isn't `#RRGGBB`. See [items](item.md).

### `item.max-stack-size` {#item-max-stack-size}

Error. `maxStackSize` is outside 1 to 99. See [items](item.md).

### `item.max-stack-size-durable` {#item-max-stack-size-durable}

Error. An item with durability has a `maxStackSize` above 1. See [items](item.md).

### `item.attribute` {#item-attribute}

Error. A modifier's `attribute` isn't an attribute id. See [items](item.md).

### `item.unknown-attribute` {#item-unknown-attribute}

Error. A modifier's attribute is one the target version doesn't have. See [items](item.md).

### `item.attribute-id` {#item-attribute-id}

Error. A modifier's `id` isn't a namespaced id, or two modifiers share one. See [items](item.md).

### `item.attribute-amount` {#item-attribute-amount}

Error. A modifier's `amount` isn't a number. See [items](item.md).

### `item.block` {#item-block}

Error. An entry of `canBreak` or `canPlaceOn` isn't a block, a `#tag` of blocks or `*`. See [items](item.md).

### `item.unknown-block` {#item-unknown-block}

Error. An entry of `canBreak` or `canPlaceOn` is a block or block tag the target version doesn't have. See [items](item.md).

### `item.tooltip-component` {#item-tooltip-component}

Error. An entry of `hiddenTooltipComponents` isn't a tooltip line an item can hide. See [items](item.md).

### `item.unknown-tooltip-component` {#item-unknown-tooltip-component}

Error. An entry of `hiddenTooltipComponents` is a component the target version doesn't have. See [items](item.md).

### `item.food` {#item-food}

Error. A `food` value is out of range. See [items](item.md).

### `item.cooldown` {#item-cooldown}

Error. A cooldown's `seconds` isn't more than 0. See [items](item.md).

### `item.cooldown-group` {#item-cooldown-group}

Error. A cooldown's `group` isn't a namespaced id. See [items](item.md).

### `item.hold-food` {#item-hold-food}

Error. An item has both `hold` and `food`. See [items](item.md).

### `item.hold-animation` {#item-hold-animation}

Error. A hold's `animation` isn't one of the game's use animations. See [items](item.md).

### `item.use-effects` {#item-use-effects}

Error. A `useEffects` value is out of range. See [items](item.md).

### `item.draw-without-ammo` {#item-draw-without-ammo}

Error. `drawWithoutAmmo` is on an item that isn't a bow. See [items](item.md).

## Recipes

### `recipe.missing` {#recipe-missing}

Error. A recipe lacks a field its type needs. See [recipes](recipe.md).

### `recipe.field` {#recipe-field}

Error. A recipe has a field its type doesn't take. See [recipes](recipe.md).

### `recipe.pattern` {#recipe-pattern}

Error. A shaped recipe's `pattern` isn't 1 to 3 equal rows of 1 to 3 characters, each in `key` or a space. See [recipes](recipe.md).

### `recipe.key` {#recipe-key}

Error. A shaped recipe's `key` has a key that isn't one character, or one the pattern doesn't use. See [recipes](recipe.md).

### `recipe.station` {#recipe-station}

Error. A station recipe's `stations` or `stationRange` isn't usable. See [recipes](recipe.md).

### `recipe.ingredients` {#recipe-ingredients}

Error. A shapeless recipe has no ingredients, or more than 9. See [recipes](recipe.md).

### `recipe.ingredient` {#recipe-ingredient}

Error. An ingredient isn't an item id (other than air) or an item tag. See [recipes](recipe.md).

### `recipe.unknown-item` {#recipe-unknown-item}

Error. An ingredient names an item the target version doesn't have. See [recipes](recipe.md).

### `recipe.unknown-tag` {#recipe-unknown-tag}

Error. An ingredient names an item tag the target version doesn't have. See [recipes](recipe.md).

### `recipe.experience` {#recipe-experience}

Error. `experience` is negative. See [recipes](recipe.md).

### `recipe.cooking-time` {#recipe-cooking-time}

Error. `cookingTime` is below 1 tick. See [recipes](recipe.md).

### `recipe.category` {#recipe-category}

Error. `category` isn't one of the recipe book tabs its type has. See [recipes](recipe.md).

## Loot tables

### `loot.pool-name` {#loot-pool-name}

Error. A pool's name isn't usable. See [loot tables](loot.md).

### `loot.range` {#loot-range}

Error. A `rolls` or `count` range is negative, has its `max` below its `min`, or goes past the most allowed. See [loot tables](loot.md).

### `loot.no-entries` {#loot-no-entries}

Warning. A pool has no entries, so it never gives anything. See [loot tables](loot.md).

### `loot.weight` {#loot-weight}

Error. An entry's `weight` is below 1. See [loot tables](loot.md).

### `loot.item-count` {#loot-item-count}

Error. An item entry's `item` has a `count`: the entry's `count` says how many. See [loot tables](loot.md).

### `loot.vanilla` {#loot-vanilla}

Error. A game loot table's id isn't a namespaced id. See [loot tables](loot.md).

### `loot.unknown-vanilla` {#loot-unknown-vanilla}

Error. A game loot table the target version doesn't have. See [loot tables](loot.md).

### `loot.chance` {#loot-chance}

Error. A `chance` condition's chance is outside 0 to 1. See [loot tables](loot.md).

### `loot.tool` {#loot-tool}

Error. A `tool` condition's tool isn't an item id (other than air) or an item tag. See [loot tables](loot.md).

### `loot.unknown-tool` {#loot-unknown-tool}

Error. A `tool` condition names an item or item tag the target version doesn't have. See [loot tables](loot.md).

### `loot.enchantment` {#loot-enchantment}

Error. An `enchantment` condition's enchantment isn't an id, or its `level` is below 1. See [loot tables](loot.md).

### `loot.unknown-enchantment` {#loot-unknown-enchantment}

Error. An `enchantment` condition names an enchantment the target version doesn't have. See [loot tables](loot.md).

### `loot.cycle` {#loot-cycle}

Error. A loot table includes itself, through its entries or theirs. See [loot tables](loot.md).

## Blocks

### `block.hardness` {#block-hardness}

Error. A block's `hardness` is below -1 or isn't a number. See [blocks](block.md).

### `block.tick` {#block-tick}

Error. A block's `tick` isn't a whole number of ticks from 1 to an hour. See [blocks](block.md).

### `block.falls` {#block-falls}

Error. A block `falls`, but it's held as tripwire or drawn by a centity, which can't fall. See [blocks](block.md).

### `block.sound` {#block-sound}

Error. A block's `sound` isn't a kind of block the game has place and break sounds for. See [blocks](block.md).

### `block.requires-tool` {#block-requires-tool}

Warning. A block `requiresTool` but names no `tool`, so no tool is the right one and nothing drops. See [blocks](block.md).

### `block.carriers` {#block-carriers}

Error. The project has more blocks of one carrier than the game has states of it to hold them in. See [blocks](block.md).

### `block.carrier` {#block-carrier}

Warning. Something about a block its carrier doesn't do: a hardness tripwire ignores, or a model a note block hides its neighbours' faces around. See [blocks](block.md).

### `block.vanilla-looks` {#block-vanilla-looks}

Warning. `client/vanilla.json` lacks the game's look of a block custom blocks are held in, or is another version's. See [blocks](block.md).

## game_block

### `game_block.blocks` {#game_block-blocks}

Error. A game block file's `blocks` is empty, or an entry isn't a block id or a `#tag`. See [format/game-block.md](game-block.md).

### `game_block.unknown` {#game_block-unknown}

Error. A game block file names a block or block tag the target version doesn't have. See [format/game-block.md](game-block.md).

### `game_block.twice` {#game_block-twice}

Error. One of the game's blocks is named twice: by two files (the project's or its packages'), or twice in one (a tag names every block in it). See [format/game-block.md](game-block.md).

### `game_block.hardness` {#game_block-hardness}

Error. A game block file's `hardness` is below -1 or isn't a number. See [format/game-block.md](game-block.md).

### `game_block.carrier` {#game_block-carrier}

Error. A game block file names a block the project's own blocks, or a package's, are held in. See [format/game-block.md](game-block.md).

### `game_block.requires-tool` {#game_block-requires-tool}

Warning. `requiresTool: false` without `drops`: the game's own drops keep the game's rule, so the wrong tool still gets nothing. See [format/game-block.md](game-block.md).

## migration

### `migration.name` {#migration-name}

Error. A migration file isn't named `NNN_name.sql` (three digits, then lowercase letters, digits and _). See [format/migrations.md](migrations.md).

### `migration.duplicate` {#migration-duplicate}

Error. Two migration files have the same number. See [format/migrations.md](migrations.md).

### `migration.gap` {#migration-gap}

Error. Migration numbers don't run 001, 002, 003 with nothing skipped. See [format/migrations.md](migrations.md).

### `migration.failed` {#migration-failed}

Error. A migration couldn't be applied to the package's database, so scripts can't use it. See [format/migrations.md](migrations.md).

## Advancements

### `advancement.icon` {#advancement-icon}

Error. An icon names neither a game item (`kind`) nor a project item (`item`), or both. See [advancements](advancement.md).

### `advancement.unknown-icon` {#advancement-unknown-icon}

Error. An icon's `kind` is an item the target version doesn't have. See [advancements](advancement.md).

### `advancement.background` {#advancement-background}

Warning. A tree's root has no `background`, or an advancement that isn't a root has one. See [advancements](advancement.md).

### `advancement.criteria` {#advancement-criteria}

Error. An advancement has no criteria, so nothing could complete it. See [advancements](advancement.md).

### `advancement.criterion-name` {#advancement-criterion-name}

Error. A criterion's name isn't usable. See [advancements](advancement.md).

### `advancement.trigger` {#advancement-trigger}

Error. A criterion's `trigger` isn't a namespaced id, or it has `conditions` without one. See [advancements](advancement.md).

### `advancement.unknown-trigger` {#advancement-unknown-trigger}

Error. A criterion's `trigger` is one the target version doesn't have. See [advancements](advancement.md).

### `advancement.requirements` {#advancement-requirements}

Error. `requirements` has an empty group, names a criterion the advancement doesn't have, or leaves one of its criteria out. See [advancements](advancement.md).

### `advancement.experience` {#advancement-experience}

Error. `experience` is negative. See [advancements](advancement.md).

### `advancement.cycle` {#advancement-cycle}

Error. An advancement is its own parent, through its parents or theirs. See [advancements](advancement.md).

## structure

### `structure.biomes` {#structure-biomes}

Error. A structure's `biomes` is empty, holds something that isn't a biome id, or a tag beside others. See [format/worlds.md](worlds.md).

### `structure.unknown-biome` {#structure-unknown-biome}

Error. A structure's `biomes` names a biome or tag the target version doesn't have. See [format/worlds.md](worlds.md).

### `structure.spread` {#structure-spread}

Error. `spacing`, `separation` or `salt` is out of range, or `separation` isn't below `spacing`. See [format/worlds.md](worlds.md).

### `structure.range` {#structure-range}

Error. `depth`, `maxDistance`, or a pool element's `weight` is out of range. See [format/worlds.md](worlds.md).

### `structure.pool` {#structure-pool}

Error. A pool's name isn't usable (or is `start`), or the pool has no elements. See [format/worlds.md](worlds.md).

### `structure.pool-element` {#structure-pool-element}

Error. A pool element names a structure the project doesn't have. See [format/worlds.md](worlds.md).

## build

### `build.part-name` {#build-part-name}

Warning. A file in a build's folder isn't one of its parts (`<x>_<y>_<z>.nbt`), so it's ignored. See [format/worlds.md](worlds.md).

### `build.part-missing` {#build-part-missing}

Error. A build's grid of parts has a gap: a part it needs isn't there. See [format/worlds.md](worlds.md).

### `build.size` {#build-size}

Error. A build has more parts along an axis than a build may. See [format/worlds.md](worlds.md).

### `build.id-taken` {#build-id-taken}

Error. A build has the id of one of the project's structures. See [format/worlds.md](worlds.md).

## wasm

### `wasm.invalid` {#wasm-invalid}

Error. A module's `module.wasm` isn't a WebAssembly module the server can run. See [format/wasm.md](wasm.md).

### `wasm.import` {#wasm-import}

Error. A module imports something the server doesn't provide: a module may import WASI's preview 1 functions and nothing else. See [format/wasm.md](wasm.md).

### `wasm.threads` {#wasm-threads}

Error. A module was built for threads (shared memory, `wasi.thread-spawn`), which the server doesn't run. See [format/wasm.md](wasm.md).

### `wasm.memory` {#wasm-memory}

Error. A module's memory is addressed by 64-bit numbers, which the server doesn't run. See [format/wasm.md](wasm.md).

### `wasm.exports` {#wasm-exports}

Warning. A module exports no function, so nothing can call it. See [format/wasm.md](wasm.md).

## Particle effects

### `particle.duration` {#particle-duration}

Error. `duration` is outside the ticks an effect may last. See [particle effects](particle-effect.md).

### `particle.emitters-empty` {#particle-emitters-empty}

Warning. An effect has no emitters, so it shows nothing. See [particle effects](particle-effect.md).

### `particle.budget` {#particle-budget}

Error. An effect can spawn more points in one tick than the limit. See [particle effects](particle-effect.md).

### `particle.emitter-name` {#particle-emitter-name}

Error. An emitter's name isn't usable. See [particle effects](particle-effect.md).

### `particle.id` {#particle-id}

Error. An emitter's `particle` isn't a particle id. See [particle effects](particle-effect.md).

### `particle.unknown` {#particle-unknown}

Error. An emitter's particle is one the target version doesn't have. See [particle effects](particle-effect.md).

### `particle.unsupported` {#particle-unsupported}

Error. An emitter's particle takes options effects can't send. See [particle effects](particle-effect.md).

### `particle.option` {#particle-option}

Error. An emitter sets an option its particle doesn't take, or lacks one it needs. See [particle effects](particle-effect.md).

### `particle.color` {#particle-color}

Error. A colour isn't `#RRGGBB`. See [particle effects](particle-effect.md).

### `particle.size` {#particle-size}

Error. `size` is outside the sizes the game draws. See [particle effects](particle-effect.md).

### `particle.block-state` {#particle-block-state}

Error. An emitter's `blockState` isn't a block state. See [particle effects](particle-effect.md).

### `particle.unknown-block` {#particle-unknown-block}

Error. An emitter's `blockState` names a block the target version doesn't have. See [particle effects](particle-effect.md).

### `particle.block-property` {#particle-block-property}

Error. An emitter's block state has a property, or a value, its block doesn't. See [particle effects](particle-effect.md).

### `particle.emission` {#particle-emission}

Error. An emitter has both or neither of `burst` and `rate`. See [particle effects](particle-effect.md).

### `particle.burst` {#particle-burst}

Error. `burst` is outside the points one burst may spawn. See [particle effects](particle-effect.md).

### `particle.rate` {#particle-rate}

Error. `rate` isn't more than 0 and at most the limit. See [particle effects](particle-effect.md).

### `particle.every` {#particle-every}

Error. `every` is set without `burst`, or isn't at least 1. See [particle effects](particle-effect.md).

### `particle.window` {#particle-window}

Error. An emitter's `start` or `end` is outside the effect. See [particle effects](particle-effect.md).

### `particle.count` {#particle-count}

Error. `count` is set without `motion: "random"`, or is out of range. See [particle effects](particle-effect.md).

### `particle.spread` {#particle-spread}

Error. `spread` is set without `motion: "random"`, or is negative. See [particle effects](particle-effect.md).

### `particle.speed` {#particle-speed}

Error. `speed` is negative. See [particle effects](particle-effect.md).

### `particle.direction` {#particle-direction}

Error. `motion: "direction"` has no `direction`. See [particle effects](particle-effect.md).

### `particle.shape` {#particle-shape}

Error. A shape's size or extent isn't usable. See [particle effects](particle-effect.md).

### `particle.distribution` {#particle-distribution}

Error. Even spacing on a shape that can't space points evenly. See [particle effects](particle-effect.md).

### `particle.spin` {#particle-spin}

Error. `spin` on a shape that can't spin. See [particle effects](particle-effect.md).

### `particle.curve-channel` {#particle-curve-channel}

Error. A curve drives a value the emitter doesn't have. See [particle effects](particle-effect.md).

### `particle.curve-conflict` {#particle-curve-conflict}

Error. A value is set and driven by a curve too. See [particle effects](particle-effect.md).

### `particle.curve-time` {#particle-curve-time}

Error. A curve key's time is outside the effect. See [particle effects](particle-effect.md).

### `particle.curve-duplicate` {#particle-curve-duplicate}

Error. Two keys of one curve are at the same tick. See [particle effects](particle-effect.md).

## terrain

### `terrain.name` {#terrain-name}

Error. A noise, cave, ore, decoration or biome area's name isn't an id. See [format/terrain.md](terrain.md).

### `terrain.limit` {#terrain-limit}

Error. A file holds more layers, noises, caves, ores, decorations or biome areas than the limit. See [format/terrain.md](terrain.md).

### `terrain.block` {#terrain-block}

Error. A block isn't a block state, or the target version has no such block or property. See [format/terrain.md](terrain.md).

### `terrain.one-block` {#terrain-one-block}

Error. A layer, the stone, the floor or a decoration names no block, or more than one of `block`, `customBlock` and `structure`. See [format/terrain.md](terrain.md).

### `terrain.noise` {#terrain-noise}

Error. A noise's frequency, octaves, lacunarity or gain is out of range. See [format/terrain.md](terrain.md).

### `terrain.height` {#terrain-height}

Error. The terrain's base, sea level, an amplitude or a biome area's terrain is out of range. See [format/terrain.md](terrain.md).

### `terrain.border` {#terrain-border}

Error. The blend radius or the jitter of the borders between biome areas is out of range. See [format/terrain.md](terrain.md).

### `terrain.layer` {#terrain-layer}

Error. A layer's or the floor's thickness is out of range. See [format/terrain.md](terrain.md).

### `terrain.cave` {#terrain-cave}

Error. A cave's threshold, heights or depth is out of range. See [format/terrain.md](terrain.md).

### `terrain.ore` {#terrain-ore}

Error. An ore names no block or two, or its size, veins or heights are out of range. See [format/terrain.md](terrain.md).

### `terrain.decoration` {#terrain-decoration}

Error. A decoration's count, chance, threshold or heights are out of range, or a structure is given a placement it can't have. See [format/terrain.md](terrain.md).

### `terrain.area` {#terrain-area}

Error. A `biomes` list of an ore, cave or decoration names a biome area the file doesn't have. See [format/terrain.md](terrain.md).

### `terrain.custom-block` {#terrain-custom-block}

Error. A custom block a terrain places is drawn by a centity, which a terrain can't place. See [format/terrain.md](terrain.md).

### `terrain.biome` {#terrain-biome}

Error. A biome area's game biome isn't a biome id, or the target version has no such biome. See [format/terrain.md](terrain.md).

### `terrain.climate` {#terrain-climate}

Error. A biome area's temperature, humidity or other climate range is outside -1 to 1, its minimum is above its maximum, or it names a climate value the file's `climate.noises` doesn't declare. See [format/terrain.md](terrain.md).

### `terrain.volume` {#terrain-volume}

Error. A biome area limited by height (`y`, `depth` or `surface`) has layers or terrain of its own, its range is empty, the islands float over one, or every area of the file is one. See [format/terrain.md](terrain.md).

### `terrain.density` {#terrain-density}

Error. A 3D noise's squash or a scale is out of range, the islands' height, thickness or threshold is, or a biome area has a density in a file without one. See [format/terrain.md](terrain.md).

### `terrain.script` {#terrain-script}

Error. A file's `script` asks for a budget or more blocks or loot tables than a script may have. See [format/terrain.md](terrain.md).

### `terrain.script-missing` {#terrain-script-missing}

Error. A file has a `script`, but there's no `terrain/<id>.lua` beside it. See [format/terrain.md](terrain.md).

### `terrain.script-unused` {#terrain-script-unused}

Warning. A `terrain/<id>.lua` is beside a file with no `script`, so it never runs. See [format/terrain.md](terrain.md).

### `terrain.script-failed` {#terrain-script-failed}

Warning. A terrain's script didn't load, raised an error or ran past its budget: what it failed at is the file's own result (a column's height, a chunk's stage). See [format/terrain.md](terrain.md).

## Biomes

### `biome.climate` {#biome-climate}

Error. A biome's downfall is outside 0 to 1, or its temperature outside -2 to 2. See [biomes](biome.md).

### `biome.color` {#biome-color}

Error. A colour isn't written `#rrggbb`. See [biomes](biome.md).

### `biome.particle` {#biome-particle}

Error. The ambient particle isn't a particle the target version has, takes options, or its probability isn't more than 0 and at most 1. See [biomes](biome.md).

### `biome.sound` {#biome-sound}

Error. A sound isn't a sound event the target version has, or a delay, chance or distance of the sounds or music is out of range. See [biomes](biome.md).

### `biome.spawn` {#biome-spawn}

Error. A spawn names no entity type the target version has, or its weight or group size is out of range. See [biomes](biome.md).

### `biome.spawn-cost` {#biome-spawn-cost}

Error. A spawn cost names no entity type the target version has, or its charge or budget isn't more than 0. See [biomes](biome.md).

### `biome.feature` {#biome-feature}

Error. A feature isn't a placed feature the target version has, or it's listed twice in one step. See [biomes](biome.md).

### `biome.feature-order` {#biome-feature-order}

Error. Two of the project's biomes list the same two features of a step in opposite orders, which the game refuses in one world. See [biomes](biome.md).

## sky

### `sky.color` {#sky-color}

Error. A sky's colour isn't written `#rrggbb` (or `#aarrggbb`, for the clouds). See [format/sky.md](sky.md).

### `sky.empty` {#sky-empty}

Error. A sky gives no colour at all. See [format/sky.md](sky.md).

### `sky.fade` {#sky-fade}

Error. A sky's fade is outside 1 to 8 steps. See [format/sky.md](sky.md).

### `sky.night` {#sky-night}

Warning. A sky that doesn't darken at night gives no sky or fog colour to hold. See [format/sky.md](sky.md).

## screen

### `screen.size` {#screen-size}

Error. A screen's (or a video's) width or height isn't 1 to 16 maps. See [format/screen.md](screen.md).

### `screen.color` {#screen-color}

Error. A screen's colour isn't written `#rrggbb`. See [format/screen.md](screen.md).

### `screen.world` {#screen-world}

Error. Where a screen hangs names something that isn't a world name. See [format/screen.md](screen.md).

### `screen.range` {#screen-range}

Error. A screen's `range` or `clickRange` is outside what it may be. See [format/screen.md](screen.md).

### `screen.volume` {#screen-volume}

Error. A screen's video's `volume` isn't 0 to 1. See [format/screen.md](screen.md).

### `screen.place` {#screen-place}

Warning. Two screens hang in the same block, so one covers part of the other. See [format/screen.md](screen.md).

## screen_font

### `screen_font.id` {#screen_font-id}

Error. A screen font is called `default`, which is the game's own map font. See [format/screen-font.md](screen-font.md).

### `screen_font.file` {#screen_font-file}

Error. A screen font's `file` isn't a picture or font file of the right kind in its folder. See [format/screen-font.md](screen-font.md).

### `screen_font.missing` {#screen_font-missing}

Error. A screen font's picture or font file isn't in its folder. See [format/screen-font.md](screen-font.md).

### `screen_font.chars` {#screen_font-chars}

Error. A bitmap screen font lists no characters, or rows of different lengths. See [format/screen-font.md](screen-font.md).

### `screen_font.field` {#screen_font-field}

Error. A screen font sets a field of the other type of font. See [format/screen-font.md](screen-font.md).

### `screen_font.size` {#screen_font-size}

Error. A screen font's size, spacing or line height is outside what it may be. See [format/screen-font.md](screen-font.md).

### `screen_font.glyph` {#screen_font-glyph}

Error. A pixels screen font's glyph isn't one character drawn in rows of `#` and `.`, all as tall. See [format/screen-font.md](screen-font.md).

### `screen_font.grid` {#screen_font-grid}

Error. A bitmap screen font's picture doesn't divide into its grid of characters. See [format/screen-font.md](screen-font.md).

## video

### `video.fps` {#video-fps}

Error. A video's `fps` isn't 1 to 20. See [format/video.md](video.md).

### `video.range` {#video-range}

Error. A video's `start` or `end` isn't a time in it. See [format/video.md](video.md).

### `video.source` {#video-source}

Error. A video's `source` isn't a file in its folder. See [format/video.md](video.md).

### `video.not-converted` {#video-not-converted}

Warning. A video hasn't been converted for screens yet, so it can't play. See [format/video.md](video.md).

### `video.audio-missing` {#video-audio-missing}

Warning. A video keeps its sound, but its converted sound isn't there. See [format/video.md](video.md).

### `video.stale` {#video-stale}

Warning. A video's converted frames were made for other settings than its file has now: convert it again. See [format/video.md](video.md).

## datapack

### `datapack.format` {#datapack-format}

Error. A `min_format` or `max_format` in `pack.mcmeta` isn't a data pack format (a number, or `[major, minor]`), or the first is later than the second. See [format/datapack.md](datapack.md).

### `datapack.version` {#datapack-version}

Error. The datapack isn't written for the data pack format of the Minecraft version it runs on, so it's left out. See [format/datapack.md](datapack.md).

### `datapack.overlay` {#datapack-overlay}

Error. An overlay's `directory` isn't a usable folder name, is `data`, or is listed twice. See [format/datapack.md](datapack.md).

### `datapack.file` {#datapack-file}

Error. A file of the datapack isn't a worldgen entry or worldgen tag of the game's (`data/<namespace>/worldgen/<registry>/…` or `data/<namespace>/tags/worldgen/<registry>/…`, as `.json`), or its id isn't usable. See [format/datapack.md](datapack.md).

### `datapack.registry` {#datapack-registry}

Warning. A file is in a folder of `worldgen/` that's no registry of the target version's: the game ignores it. See [format/datapack.md](datapack.md).

### `datapack.namespace` {#datapack-namespace}

Error. A file is in a namespace other than the project's own or `minecraft` (a package's datapacks only write its own). See [format/datapack.md](datapack.md).

### `datapack.override` {#datapack-override}

Error. A `minecraft` file replaces nothing of the game's: the game's namespace is only for replacing its own; new entries go in the project's. See [format/datapack.md](datapack.md).

### `datapack.conflict` {#datapack-conflict}

Error. Two of the project's datapacks, or a datapack and a resource of the project's, write the same entry. See [format/datapack.md](datapack.md).

### `datapack.tag` {#datapack-tag}

Error. A tag file isn't `{ "values": [...] }` with ids, `#tags` or `{ "id": … }` in it. See [format/datapack.md](datapack.md).

### `datapack.reference` {#datapack-reference}

Error. A worldgen file names an entry (a feature, a biome, a noise…) that neither the game nor the project has, or a namespace it can't name. See [format/datapack.md](datapack.md).

## dimension-type

### `dimension-type.height` {#dimension-type-height}

Error. `minY` or `height` isn't a multiple of 16, the height is under 16, or the world would reach below -2032 or above 2032. See [format/dimension-type.md](dimension-type.md).

### `dimension-type.logical-height` {#dimension-type-logical-height}

Error. `logicalHeight` is below 0 or above the height. See [format/dimension-type.md](dimension-type.md).

### `dimension-type.light` {#dimension-type-light}

Error. `ambientLight` is outside 0 to 1, a monster spawn light level outside 0 to 15, or its minimum above its maximum. See [format/dimension-type.md](dimension-type.md).

### `dimension-type.color` {#dimension-type-color}

Error. A colour isn't written `#rrggbb` (or `#aarrggbb`, for the clouds). See [format/dimension-type.md](dimension-type.md).

### `dimension-type.number` {#dimension-type-number}

Error. `coordinateScale` is outside 0.00001 to 30000000, or `cloudHeight` outside -2032 to 2032. See [format/dimension-type.md](dimension-type.md).

### `dimension-type.infiniburn` {#dimension-type-infiniburn}

Error. `infiniburn` isn't a block tag written `#namespace:path`. See [format/dimension-type.md](dimension-type.md).

### `dimension-type.infiniburn-tag` {#dimension-type-infiniburn-tag}

Warning. `infiniburn` names a block tag the target version doesn't have: no fire burns forever. See [format/dimension-type.md](dimension-type.md).

## Cutscenes

### `cutscene.length` {#cutscene-length}

Error. A cutscene's length isn't more than 0 and at most the longest allowed. See [cutscenes](cutscene.md).

### `cutscene.track-empty` {#cutscene-track-empty}

Error. The camera's position or rotation track has no keys. See [cutscenes](cutscene.md).

### `cutscene.key-time` {#cutscene-key-time}

Error. A key's or cue's time is outside the cutscene. See [cutscenes](cutscene.md).

### `cutscene.key-duplicate` {#cutscene-key-duplicate}

Error. Two keys of one track are at the same time. See [cutscenes](cutscene.md).

### `cutscene.key-limit` {#cutscene-key-limit}

Error. A track or the cues hold more entries than the limit. See [cutscenes](cutscene.md).

### `cutscene.position` {#cutscene-position}

Error. A camera position is beyond the world's limit. See [cutscenes](cutscene.md).

### `cutscene.pitch` {#cutscene-pitch}

Error. A camera's pitch is outside -90 to 90 degrees. See [cutscenes](cutscene.md).

### `cutscene.cue-empty` {#cutscene-cue-empty}

Error. A cue has neither an `event` nor `text`. See [cutscenes](cutscene.md).

### `cutscene.cue-event` {#cutscene-cue-event}

Error. A cue's `event` isn't a usable name. See [cutscenes](cutscene.md).

### `cutscene.cue-duration` {#cutscene-cue-duration}

Error. A cue's `duration` isn't more than 0, or is set without `text`. See [cutscenes](cutscene.md).

## Resource packs

### `resource_pack.key` {#resource_pack-key}

Error. A skin, glyph, item model, tooltip or equipment key isn't an id. See [resource packs](resource-pack.md).

### `resource_pack.on-demand` {#resource_pack-on-demand}

Error. An `onDemand` pack draws something every player's game needs: blocks, the HUD, or the game's own textures, models or sounds. See [resource packs](resource-pack.md).

### `resource_pack.texture-path` {#resource_pack-texture-path}

Error. A texture isn't a `.png` path inside the resource pack's `textures/` folder. See [resource packs](resource-pack.md).

### `resource_pack.texture-missing` {#resource_pack-texture-missing}

Error. A texture names a file that doesn't exist. See [resource packs](resource-pack.md).

### `resource_pack.height` {#resource_pack-height}

Error. A skin's or glyph's `height` isn't positive. See [resource packs](resource-pack.md).

### `resource_pack.ascent` {#resource_pack-ascent}

Error. A skin's or glyph's `ascent` exceeds its `height`; Minecraft refuses to load it. See [resource packs](resource-pack.md).

### `resource_pack.offset` {#resource_pack-offset}

Error. A skin's `offset` is further than one move can go. See [resource packs](resource-pack.md).

### `resource_pack.image` {#resource_pack-image}

Warning. A skin's picture can't be read, so the title's words start after the art instead of over it. See [resource packs](resource-pack.md).

### `resource_pack.picture-size` {#resource_pack-picture-size}

Error. A skin's, glyph's or HUD element's picture is over 256 pixels tall, or over 256 wide and doesn't cut into equal pieces of 256 or less: Minecraft draws it blank. See [resource packs](resource-pack.md).

### `resource_pack.too-many` {#resource_pack-too-many}

Error. A resource pack holds more skins than a font has characters for. See [resource packs](resource-pack.md).

### `resource_pack.too-many-glyphs` {#resource_pack-too-many-glyphs}

Error. The project's resource packs hold more glyphs between them than a font has characters for. See [resource packs](resource-pack.md).

### `resource_pack.tooltip-empty` {#resource_pack-tooltip-empty}

Warning. A tooltip has neither a background nor a frame. See [resource packs](resource-pack.md).

### `resource_pack.equipment-empty` {#resource_pack-equipment-empty}

Warning. An equipment look has no layer, so nothing is drawn when it's worn. See [resource packs](resource-pack.md).

### `resource_pack.item-look` {#resource_pack-item-look}

Error. An item look needs one of `texture`, `block` and `model`, and `parent` only goes with a texture. See [resource packs](resource-pack.md).

### `resource_pack.hud` {#resource_pack-hud}

Error. A `hud` element's key is taken by another, or its place, segments or icons aren't ones it can be drawn with. See [resource packs](resource-pack.md).

### `resource_pack.hud-hide` {#resource_pack-hud-hide}

Warning. `hud.hide` needs the game's HUD sprites, which `client/vanilla.json` lacks or has from another version. See [resource packs](resource-pack.md).

### `resource_pack.vanilla-missing` {#resource_pack-vanilla-missing}

Warning. `hud.replace`, `textures` or `models` needs the game's sprite, texture and model names and sizes, which `client/vanilla.json` lacks (for a texture or model the editor hasn't looked up yet) or has from another version. See [resource packs](resource-pack.md).

### `resource_pack.replace-name` {#resource_pack-replace-name}

Error. `hud.replace`, `textures` or `models` names a sprite, texture or model the game doesn't have, or one another resource pack replaces too. See [resource packs](resource-pack.md).

### `resource_pack.replace-size` {#resource_pack-replace-size}

Error. A `textures` replacement of a screen's sheet (`gui/…`, not `gui/sprites/`) or a colour map isn't the pixel size of the game's texture it replaces. See [resource packs](resource-pack.md).

### `resource_pack.replace-shape` {#resource_pack-replace-shape}

Warning. A `textures` replacement isn't the shape of the game's texture (any size of the same shape is fine), or isn't one square frame of a texture the game animates and has no `.mcmeta` of its own. See [resource packs](resource-pack.md).

### `resource_pack.model-file` {#resource_pack-model-file}

Error. A `model` names a file that isn't in the resource pack's `models/` folder, or isn't a JSON model. See [resource packs](resource-pack.md).

### `resource_pack.model` {#resource_pack-model}

Error. A model file's element, rotation, face or display isn't one the game reads. See [resource packs](resource-pack.md).

### `resource_pack.model-bounds` {#resource_pack-model-bounds}

Error. A model's element reaches outside -16 to 32, which the game refuses. See [resource packs](resource-pack.md).

### `resource_pack.model-texture` {#resource_pack-model-texture}

Error. A model's face names a texture variable the model doesn't give, or a texture in a namespace nothing serves. See [resource packs](resource-pack.md).

### `resource_pack.model-display` {#resource_pack-model-display}

Warning. A model's display translation or scale is past what the game clamps it to. See [resource packs](resource-pack.md).

### `resource_pack.block-faces` {#resource_pack-block-faces}

Error. A block look has no texture for some face (give it a texture, or one for each face), or has a model and textures both. See [resource packs](resource-pack.md).

### `resource_pack.sound-key` {#resource_pack-sound-key}

Error. A key in `sounds` isn't a sound key, or one of the game's sound events (`minecraft:…`). See [resource packs](resource-pack.md).

### `resource_pack.sound-event` {#resource_pack-sound-event}

Error. A `sounds` key replaces a sound event the target version doesn't have, or one another resource pack replaces too. See [resource packs](resource-pack.md).

### `resource_pack.sound-replace` {#resource_pack-sound-replace}

Error. A replacement of one of the game's sound events lists no `files` to play instead. See [resource packs](resource-pack.md).

### `resource_pack.sound-name` {#resource_pack-sound-name}

Error. An `.ogg` file under `sounds/` is named so it can't be a sound event. See [resource packs](resource-pack.md).

### `resource_pack.sound-file` {#resource_pack-sound-file}

Warning. A file under `sounds/` isn't Ogg Vorbis, so it isn't a sound. See [resource packs](resource-pack.md).

### `resource_pack.sound-files` {#resource_pack-sound-files}

Error. A sound's `files` is empty. See [resource packs](resource-pack.md).

### `resource_pack.sound-path` {#resource_pack-sound-path}

Error. An entry of a sound's `files` isn't an `.ogg` path inside the resource pack's `sounds/` folder. See [resource packs](resource-pack.md).

### `resource_pack.sound-missing` {#resource_pack-sound-missing}

Error. A sound plays a file that doesn't exist. See [resource packs](resource-pack.md).

### `resource_pack.sound-volume` {#resource_pack-sound-volume}

Error. A sound's `volume` isn't positive. See [resource packs](resource-pack.md).

### `resource_pack.sound-pitch` {#resource_pack-sound-pitch}

Error. A sound's `pitch` isn't positive. See [resource packs](resource-pack.md).

## workspace

### `workspace.name` {#workspace-name}

Error. The workspace has no `name`. See [format/workspace.md](workspace.md).

### `workspace.project-path` {#workspace-project-path}

Error. An entry in `projects` isn't a relative folder, or one followed by `/*`. See [format/workspace.md](workspace.md).

### `workspace.missing-project` {#workspace-missing-project}

Error. A folder in `projects` has no `netherforge.json`, so it isn't a project. See [format/workspace.md](workspace.md).

### `workspace.empty-pattern` {#workspace-empty-pattern}

Warning. A `folder/*` in `projects` has no project directly in it. See [format/workspace.md](workspace.md).

### `workspace.server-name` {#workspace-server-name}

Error. A server's name in `network.servers` isn't lowercase letters, digits, `_` and `-`. See [format/workspace.md](workspace.md).

### `workspace.server-project` {#workspace-server-project}

Error. A server in `network.servers` runs a folder that isn't one of the workspace's projects. See [format/workspace.md](workspace.md).

### `workspace.arrival` {#workspace-arrival}

Error. `network.arrival` isn't one of the network's servers. See [format/workspace.md](workspace.md).

### `workspace.unknown-server` {#workspace-unknown-server}

Warning. A script sends players to a server the workspace's dev network doesn't have. See [format/workspace.md](workspace.md).

## The server

### `runtime.minecraft-unsupported` {#runtime-minecraft-unsupported}

Error. The project targets a Minecraft version this server's NetherForge isn't built for. See [the dev loop](../guide/dev-loop.md).

### `runtime.unbundled` {#runtime-unbundled}

Error. A server without the editor runs a project that has dependencies: it runs bundles `netherforge build` writes, and resolves nothing itself. See [packages](packages.md).

### `runtime.bundle-hash` {#runtime-bundle-hash}

Error. A package in a bundle isn't what the bundle says it is: a file was changed, added or removed. See [packages](packages.md).

### `runtime.web-disabled` {#runtime-web-disabled}

Warning. A package declares `requires.web`, but the server owner hasn't turned web routes on (`web.enabled`), so its routes aren't served. See [the project format](project.md).

### `runtime.network-disabled` {#runtime-network-disabled}

Warning. A package declares `requires.network`, but the server isn't part of a network (`network.enabled`), so there are no other servers to talk to. See [the project format](project.md).

### `runtime.bots-disabled` {#runtime-bots-disabled}

Warning. A package declares `requires.bots`, but the server owner hasn't allowed bots (`bots.enabled`), so its scripts can't join any. See [the project format](project.md).

### `runtime.plugin-missing` {#runtime-plugin-missing}

Error. A package declares a plugin in `requires.plugins` that isn't enabled on the server: what needs it fails until it is. See [the project format](project.md).

### `runtime.pack-format` {#runtime-pack-format}

Warning. The server doesn't say which resource pack format it uses, so no resource pack was built. See [the dev loop](../guide/dev-loop.md).

### `runtime.blocks` {#runtime-blocks}

Error. The server can't hold the project's blocks: it has no states of their carrier for them, or won't stop working them out itself. See [blocks](block.md).

### `runtime.terrain` {#runtime-terrain}

Warning. A terrain can't be used as written: an ore's custom block has no state to be held in, the server asks for its main world's terrain and `netherforge.json` names none, or `netherforge.json` gives the main world a seed (its seed is the server's). See [format/terrain.md](terrain.md).

### `runtime.dimension-type` {#runtime-dimension-type}

Warning. A world can't have the dimension `netherforge.json` names for it: it was made with another (a world keeps the one it was made with), or the server hasn't the dimension type (it learns them only at start). See [format/dimension-type.md](dimension-type.md).

### `runtime.restart` {#runtime-restart}

Warning. Something the server learns only at start (advancements, biomes, dimension types, the main world's terrain) changed since it started: it runs the old one until it restarts. See [the dev loop](../guide/dev-loop.md).

### `runtime.datapack` {#runtime-datapack}

Error. The server refused the project's datapacks as it started (its own message says why), so it runs without them until they change. See [format/datapack.md](datapack.md).

### `runtime.wasm` {#runtime-wasm}

Error. The server couldn't compile a WebAssembly module (its own message says why: it's invalid, too large for the owner's limits, or uses something the server doesn't run), so scripts can't load it until it changes. See [format/wasm.md](wasm.md).

## settings

### `settings.file` {#settings-file}

Warning. A server's settings file isn't a JSON object, so the package's settings have their defaults. See [format/settings.md](settings.md).

### `settings.value` {#settings-value}

Warning. A server's settings file holds a value a setting can't have, so the setting has its default. See [format/settings.md](settings.md).

### `settings.unknown` {#settings-unknown}

Warning. A server's settings file holds a value for a setting the package doesn't declare. See [format/settings.md](settings.md).

## Particles on the server

### `particles.budget` {#particles-budget}

Warning. More particle points were due in one tick than the server sends, so some were skipped. See [the dev loop](../guide/dev-loop.md).
