# Buffs

`buffs/<id>.json` is a buff or a debuff: something scripts put on a player or
a centity for a while (`player:add_buff("ironskin", 20 * 60)`), which the
server counts down. While it lasts it adds its `stats` to theirs, which
scripts read with `stat` (`player:stat("defense")`) beside the modifiers they
set themselves (`set_modifiers`): a buff's numbers live in its file, not in a
script's table. Its `name`, `icon` and `color` are for a HUD or a menu to
show it by (`player:buffs()`). See [`Player`](../reference/player.md) and
[`Centity`](../reference/centity.md).

```json
{
  "$schema": "../.netherforge/schema/buff.schema.json",
  "name": "<gray>Ironskin",
  "icon": "ui/buffs/ironskin",
  "color": "#8a8a98",
  "stats": { "defense": 8 },
  "cures": ["frail"],
  "maxTicks": 6000
}
```

| Key         | Meaning                                                                                                   | Default   |
| ----------- | --------------------------------------------------------------------------------------------------------- | --------- |
| `name`      | What players read it as (MiniMessage).                                                                    | none      |
| `icon`      | `<pack>/<key>`: a [glyph](resource-pack.md) of the project's resource packs to draw it by.                | none      |
| `color`     | Its colour, `#rrggbb`.                                                                                    | none      |
| `debuff`    | Whether it's bad for whoever has it.                                                                      | false     |
| `stats`     | What it adds to stats while it lasts: a number per stat name (lowercase letters, digits and `_`).         | none      |
| `cures`     | Buffs it takes away as it comes.                                                                          | none      |
| `blockedBy` | Buffs that keep it from taking hold while they last.                                                      | none      |
| `stacking`  | Adding it again while it lasts: `longest` keeps the longer time, `add` adds them, `replace` starts again. | `longest` |
| `maxTicks`  | The most ticks it can have left, at least 1.                                                              | no limit  |

A stat is just a name: the engine adds up what every buff and every modifier
give it, and scripts decide what it means (`defense` taken off damage,
`move` added to a walk). Buffs aren't saved: after a restart nobody has any.
Saving a buff's file changes it for everyone who has it now.
