# Advancements

An advancement is one of the project's own quests: a step a player
completes, shown on the game's advancements screen (the `L` key) with a
toast and a chat line when it's done. Each is one file,
`advancements/<id>.json`, with nothing beside it. Advancements make trees:
one with no `parent` is a tree's root, which the screen shows as a tab of
its own, and the rest hang off their parents.

```json
{
  "$schema": "../.netherforge/schema/advancement.schema.json",
  "parent": "adventurer",
  "display": {
    "icon": { "item": "ruby" },
    "title": "Treasure Hunter",
    "description": "Roll for <glyph:ui/coin> treasure three times with /treasure",
    "frame": "goal"
  },
  "criteria": {
    "first": {},
    "second": {},
    "third": {}
  },
  "experience": 10
}
```

The id is the file's name. Scripts grant, revoke and read an advancement by
it, with the player's advancement functions
(`player:grant_advancement("treasure_hunter", "first")`, see
[`Player`](../reference/player.md)), and hear `player_complete_advancement`
when one is completed. On the server it's `<namespace>:<id>`
(`showcase:treasure_hunter`), which is also what `event.advancement` says and
what `/advancement grant` takes.

| Key            | Meaning                                                                                                                                                                        |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `parent`       | The advancement it follows, by [reference](references.md): the project's own (`"adventurer"`), or one a package exports (`"acme:quests"`). None for a tree's root.             |
| `display`      | How it's [shown](#display). Without one it's never shown: it only tracks something, for scripts to read.                                                                       |
| `criteria`     | What completes it, by [name](#criteria). At least one (`advancement.criteria`).                                                                                                |
| `requirements` | Which criteria complete it: a list of groups, each needing one of its criteria. Default: every criterion, each its own group (all of them). See [requirements](#requirements). |
| `experience`   | Experience points a player gets on completing it. Default 0, never negative (`advancement.experience`).                                                                        |

An advancement whose parents lead back to itself is an error
(`advancement.cycle`).

## Display

| Key           | Meaning                                                                                                                                                                                                                                                                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `icon`        | The item on its frame: a game item, `{ "kind": "minecraft:diamond" }`, or a [project item](item.md), `{ "item": "ruby" }`, drawn with its look. One or the other (`advancement.icon`). `itemModel` (a resource pack's item model, `ui/ruby`) and `glint` (the enchanted shimmer) change the look; a project item's own are used when they're left out. |
| `title`       | Its name, MiniMessage (glyph tags too).                                                                                                                                                                                                                                                                                                                |
| `description` | What to do for it, under the title on the screen, MiniMessage.                                                                                                                                                                                                                                                                                         |
| `frame`       | `task` (a square: "Advancement Made!", the default), `goal` (rounded: "Goal Reached!") or `challenge` (spiked: "Challenge Complete!").                                                                                                                                                                                                                 |
| `background`  | For a tree's root: the texture its tab is tiled with, by the client's id without `textures/` and `.png`: `minecraft:block/stone_bricks`. A root without one shows the missing texture; an advancement with a parent doesn't show one (both `advancement.background`, a warning).                                                                       |
| `toast`       | A toast at the top right when it's completed. Default true.                                                                                                                                                                                                                                                                                            |
| `announce`    | A line in everyone's chat when it's completed. Default true.                                                                                                                                                                                                                                                                                           |
| `hidden`      | Kept off the screen until it's completed (with everything below it). Default false.                                                                                                                                                                                                                                                                    |

## Criteria

Each criterion is one way towards completing the advancement, by a name
(lowercase letters, digits and `_`: `advancement.criterion-name`).

| Key          | Meaning                                                                                                                                                                                                                                                |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `trigger`    | One of the game's triggers, `"minecraft:inventory_changed"`, so the game meets it when that happens, as for its own advancements. One the target version doesn't have is an error once the editor has the game's data (`advancement.unknown-trigger`). |
| `conditions` | The trigger's conditions, handed to the game as written, in its own advancement format (only with a `trigger`: `advancement.trigger`).                                                                                                                 |

A criterion with neither (`{}`) is met only when a script grants it:
`player:grant_advancement("treasure_hunter", "first")` meets one criterion,
`player:grant_advancement("treasure_hunter")` all of them. Scripts can grant
and revoke any criterion, triggered or not, and
`player:advancement_progress(id)` lists which are met.

```json partial
"criteria": {
  "found": {
    "trigger": "minecraft:inventory_changed",
    "conditions": { "items": [{ "items": "minecraft:diamond" }] }
  },
  "given": {}
}
```

A trigger's conditions are the game's, not NetherForge's: what they may say
is the target version's [advancement format](https://minecraft.wiki/w/Advancement_definition),
and NetherForge doesn't check them. The game refuses an advancement whose
conditions it can't read, and says why in the server's log.

## Requirements

By default an advancement is completed once every criterion is met. To
complete it on any one of them, or some combination, list groups: every
group must have one of its criteria met.

```json partial
"requirements": [["found", "given"]]
```

completes on `found` or `given`; `[["a"], ["b", "c"]]` on `a` and either of
`b` or `c`. Every name must be one of `criteria`, and every criterion must be
in some group, or the game refuses the advancement (`advancement.requirements`).

## Changing advancements

The game only reads advancements as it starts, so a change needs a server
restart. On the editor's dev server, saving an advancement restarts the
server for you. On a server of your own, `/nf reload` says a restart is
needed (`runtime.restart`, a warning), and until then the server keeps the
advancements it started with. An advancement with errors is left out.

On the server, an advancement's id is `<namespace>:<id>`
(`showcase:treasure_hunter`): that's what `event.advancement` says, and what the
game's `/advancement` command takes.

## In packages

A package's advancements are in its own namespace on the server
(`library:gem_collector`), and its references mean what they do in it. A
project can make one of its own the child of an advancement a package
exports, adding to the package's tree; scripts name a package's
advancements as `ns:id`, and only the exported ones.
