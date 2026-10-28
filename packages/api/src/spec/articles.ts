/**
 * Reference pages that aren't about one class: the rules every name in the
 * API follows, and the coordinate spaces positions are in. `pnpm generate`
 * writes each into `docs/reference/` and lists it in the reference's index.
 */
import type { Article } from '../types.ts'

const naming: Article = {
  file: 'naming.md',
  title: 'Naming rules',
  summary: 'how every name in the API is formed, so you can guess one you have not seen',
  body: `
Every name in the API follows these rules, so once you know one part of it you can guess the
rest. The spec's own tests check the ones a machine can.

## Shape

- Functions, methods, fields, events and option keys are \`snake_case\`: \`play_animation\`,
  \`hit_position\`. Types are \`PascalCase\`: \`Centity\`, \`Vec3\`, \`ClickEvent\`.
- Namespaces are called with a dot, handles with a colon: \`nf.players.get(...)\`,
  \`player:send_message(...)\`.
- Words are spelled out: \`position\`, not \`pos\`; \`direction\`, not \`dir\`; \`arguments\`, not
  \`args\`.
- Units are in the docs, not the names. Rotations are degrees, durations are ticks (20 a
  second), distances are blocks, unless a page says otherwise.

## Getters, setters and questions

- A getter is a noun, with no \`get_\`: \`name()\`, \`position()\`, \`health()\`. It returns **one
  value**, \`nil\` when there's nothing to say (\`translation()\` is a \`Vec3?\`). The one
  exception is \`Vec3:unpack()\`.
- A setter is \`set_\` and the getter's noun, and takes one value: \`health()\` and
  \`set_health(n)\`. Values that change together are one table:
  \`set_brightness({ block_light = 15, sky_light = 15 })\`.
- A keyed getter or setter takes the key first: \`game_rule(rule)\` and
  \`set_game_rule(rule, value)\`, \`item(index)\` and \`set_item(index, item)\`.
- A yes-or-no getter is \`is_<adjective>()\`, \`has_<noun>()\` or \`can_<verb>()\`, and its setter
  drops the prefix: \`is_visible()\` and \`set_visible(b)\`, \`has_gravity()\` and
  \`set_gravity(b)\`, \`can_fly()\` and \`set_can_fly(b)\`. The one exception is \`exists()\`.
- A list is a plural noun and returns an array: \`players()\`, \`nodes()\`, \`viewers()\`.
- A yes-or-no field of a plain table is a bare adjective: \`event.cancelled\`,
  \`event.in_menu\`, \`event.first_join\`.

## What a thing is

| Name | Means |
| --- | --- |
| \`id()\` | The handle's own identity: the string to store to get the same handle back later. A centity instance's id, a player's or entity's UUID, a dialog's id. |
| \`exists()\` | Whether the thing is there now: a centity still in the world, an entity alive and loaded, a player online, a menu window open, a file on disk. |
| \`kind()\` | The id of what it's an instance of: the centity's folder (\`"crate"\`), \`"minecraft:zombie"\`, \`"minecraft:stone"\`. An item table's \`kind\` field is the same. |
| \`name()\` | What a person reads: a player's name, a node's name, a file's name. |

A handle to something that has gone (a player who left, a removed centity) answers \`nil\` or
\`false\` rather than erroring; a mistake (an unknown event, centity or animation, a wrong
argument type) is an error at your script's line.

## One verb per idea

| Verb | Means | For example |
| --- | --- | --- |
| \`run\` | run a command line | \`player:run(command)\`, \`nf.server.run(command)\` |
| \`send_<thing>\` | show text to one player | \`send_message\`, \`send_actionbar\`, \`send_title\` |
| \`broadcast\` | show text to everyone online and the console | \`nf.server.broadcast(text)\` |
| \`open_<screen>\`, \`close_<screen>\` | put a screen in front of a player, or take it away | \`player:open_menu\`, \`player:open_dialog\`, \`dialog:open_for(player)\` |
| \`spawn\`, \`spawn_<thing>\` | make something in the world | \`nf.centities.spawn\`, \`world:spawn_entity\`, \`world:spawn_particle\` |
| \`create\` | make something a script owns that isn't in the world | \`nf.bossbars.create\`, \`nf.menus.create\`, \`nf.dialogs.create\` |
| \`remove\` | take something out of the world or the game | \`centity:remove()\`, \`entity:remove()\`, \`bossbar:remove()\` |
| \`delete\` | erase stored data | \`file:delete()\` |
| \`cancel\` | stop something pending, or what an event is about | \`task:cancel()\`, \`subscription:cancel()\`, \`event:cancel()\` |
| \`clear\` | empty a container or a display | \`menu:clear()\`, \`inventory:clear()\`, \`player:clear_title()\` |
| \`play_<thing>\`, \`stop_<thing>\` | start or stop something playing | \`play_animation\`, \`play_sound\`, \`stop_sound\` |
| \`add_<thing>\`, \`remove_<thing>\`, \`has_<thing>\` | what's in a collection | tags, effects, passengers, items |
| \`apply_<thing>\` | push a physics body | \`apply_impulse\`, \`apply_force\` |
| \`get(key)\`, \`all(filter?)\` | look one thing up, or list them, in a namespace | \`nf.players.get\`, \`nf.centities.all({ kind = "crate" })\` |
| \`on\`, \`once\`, \`emit\` | events | every class with events |
| \`teleport\` | move a whole thing somewhere | centities, entities, players |
| \`look_at\` | turn to face a point in the world | nodes, centities, entities, players |

## Positions and facing

- \`position()\` is a \`Vec3\` in world space, \`location()\` a [Location](location.md) (a world,
  a position and which way it faces), \`world()\` a [World](world.md).
- A name says its space only when the thing's own space isn't the world's: a node's own
  transform is \`translation()\` (in its parent's space), so where it is in the world is
  \`world_position()\`. See [Coordinate spaces](spaces.md).
- Facing is \`yaw()\`, \`pitch()\` and \`direction()\` (a unit \`Vec3\`), with \`set_yaw\`,
  \`set_pitch\` and \`look_at(point)\`.

## Events

- A server-wide event on \`nf.on\` is \`<subject>_<verb>\`: \`player_join\`, \`block_break\`,
  \`menu_click\`. An event on a handle is a bare verb: \`click\`, \`press\`, \`open\`, \`tick\`,
  \`animation_end\`. A custom event has a \`:\` in its name: \`"shop:purchased"\`.
- Every handler gets a table whose type is named \`<Something>Event\`.
- A field name means the same in every payload: \`click\` is the kind of click, \`target\` what
  was hit, \`key\` a dialog button's key. See [Events](events.md).

## Option tables

- Optional parameters past one or two go in a last \`options\` table:
  \`this:play_animation("open", { speed = 2 })\`.
- Option tables are strict: a key that isn't one of the table's is an error, as it is in an
  item table.
`,
}

const spaces: Article = {
  file: 'spaces.md',
  title: 'Coordinate spaces',
  summary: 'which space each position is in, and how to convert between them',
  body: `
Every position, offset, direction and velocity is a [Vec3](vec3.md), and every function that
takes or returns one says which space it's in. There are three:

| Space | Origin and axes | Used by |
| --- | --- | --- |
| **world** | the world's | players, entities, blocks, particles, sounds, raycasts, and every physics velocity, impulse and force |
| **centity** | the centity's anchor (\`centity:position()\`), turned by its yaw | its root nodes' translations |
| **parent** | the node's parent node | a node's \`translation()\`, \`rotation()\` and \`scale()\` |

A root node's parent space is the centity's space; any other node's is its parent node's own
space. Rotations are Euler angles in degrees.

## Converting

| From | To | Call |
| --- | --- | --- |
| world | centity | \`centity:to_local(point)\` |
| centity | world | \`centity:to_world(point)\` |
| world | a node's own space (where its children sit) | \`node:to_local(point)\` |
| a node's own space | world | \`node:to_world(point)\` |
| a direction in a node's own space | world | \`node:world_direction(direction)\` (rotation only) |

\`node:world_position()\` and \`node:world_rotation()\` say where a node is in the world as it's
shown now, animation and physics included.

\`\`\`lua
-- the top centre of a crate, wherever it has rolled to
local crate = assert(this:node("crate"))
local top = crate:to_world(vec3(0.5, 1, 0.5))
if top then
  crate:apply_impulse_at(vec3(0, 0, 4), top)
end
\`\`\`

## Physics is in world space

Velocities, impulses, forces and the point an impulse is applied at are all world space, so
what a click reports goes straight back in: \`node:apply_impulse_at(impulse, event.hit_position)\`.

## A centity's yaw

A centity turns about the vertical only: \`centity:yaw()\`, \`centity:set_yaw(degrees)\` and
\`centity:look_at(point)\`, and \`nf.centities.spawn(kind, location)\` uses the location's yaw.
The yaw turns the centity's space, so its root nodes, hitboxes and physics turn with it. For
pitch or roll, rotate a root node.
`,
}

const packages: Article = {
  file: 'packages.md',
  title: 'Names across packages',
  summary:
    "how a script names its own package's things and the packages' it depends on, and how names read back",
  body: `
A project can depend on packages (\`netherforge.json\`'s \`dependencies\`, see the
[packages format](../format/packages.md)), which run on the same server beside it. Every
script belongs to one package: the project's own, or the package its file is in.

## Naming things

A name a script hands the API means what it would in one of its own package's files:

- **Bare** (\`"ruby"\`, \`"ui/coin"\`, \`require("util")\`): its own package's.
- **\`namespace:name\`** (\`"library:gem"\`, \`"<glyph:library:gems/gem>"\`,
  \`require("library:greetings")\`): a package its package depends on, which must export it
  (its \`netherforge.json\`'s \`exports\`). Anything else is an error naming the package:
  \`package "library" doesn't export its item "secret", so only it can use it\`.

That holds for every name of a thing a script uses: items, menus, dialogs, centities, particle
effects, recipes, loot tables, HUD elements and modules. Looks are the exception (below). A package's
code calling another package's function is still that package's code: the library's
\`nf.items.create("gem")\` makes the library's gem whoever called it.

An \`Item\` table is a value, not a use: any script may hold, read, compare and hand back any
stack, whatever package's item or look it carries, as long as that's there. Making a stack from
an item's definition (\`nf.items.create\`) is a use, and needs the export.

## Looks are values

A resource pack's looks (item models, glyphs, skins, tooltips, equipment looks and sounds)
are values too. Showing a look uses nothing of its package's, and every player's game
already has them all. So wherever a script names a look (\`node:set_model\`, an \`Item\`'s
\`item_model\`, \`tooltip_style\` and \`equipment.asset\`, a menu skin, a \`<glyph:…>\` tag in
[Text](index.md#types), \`nf.text.glyph\`, a boss bar icon, \`play_sound\`):

- **Bare** (\`"ui/coin"\`) is its own package's, as for every name.
- **\`namespace:<pack>/<key>\`** may be **any loaded package's** look: the project's, a
  package it depends on, or a package that depends on it, exported or not, as long as that
  package has it. A package can't name the projects that use it in its files, but it can
  show a look one hands it.
- A namespace no package on the server has is an error:
  \`no package on this server has the namespace "chess_srv"\`. A sound's is the server's
  to look up instead, since the game and other plugins have sounds of their own.

This holds only for what scripts do at runtime. Files are still checked as before: a
package's files name only its own looks and what its dependencies export, so a package is
validated on its own, and nothing a project does can make it valid or invalid.

This is how a project hands a package looks to draw with. Give it full names, which
[\`nf.looks.qualify\`](nf.looks.md) writes for you:

\`\`\`lua
-- In the project chess_server, which depends on the package chess:
local board = nf.centities.spawn("chess:board", here)
board:data().skin = { pawn = nf.looks.qualify("nether/pawn") } -- "chess_server:nether/pawn"

-- In chess's board script, today or after a restart:
this:node("pawn"):set_model(this:data().skin.pawn)
\`\`\`

A full name is a plain string, so it keeps its meaning in saved data (\`this:data()\`), across
chunk loads and restarts. A bare name handed over would mean the *package's* look of that
name. When the package has no such look but another loaded package does, the error says so
and gives the full name.

## Names read back

A name the API hands a script is written as that script's package writes it: bare for its own,
\`namespace:name\` for another's. The library's gem is \`"gem"\` to the library's scripts and
\`"library:gem"\` to the project's; the project's ruby is \`"ruby"\` to the project and
\`"shop:ruby"\` to the library (with \`shop\` the project's namespace). That's every name read
back: \`stack.item\`, \`stack.item_model\`, \`nf.items.id\`, \`item:id()\`, \`centity:kind()\`, \`menu:id()\`,
\`node:model()\`, glyph tags in text, and an event's fields, which each package's handlers read in
their own words. A look from a package that depends on the reader's reads back in full too:
a chess piece showing the project's pawn says \`"chess_server:nether/pawn"\` to chess's scripts
and \`"nether/pawn"\` to the project's.

An \`Item\` table keeps the words it was handed out in: one the project read, passed to a
library function, still says \`"ruby"\`. The API reads it in those words whichever package's
code hands it back, so \`nf.items.id(stack)\` in the library answers \`"shop:ruby"\`. Compare
stacks a caller handed you through \`nf.items.id\`, not \`stack.item\`. A plain string has no
such memory: a function taking a name from another package's code should be handed it in full
(\`"shop:ruby"\`).

## One edge: tail calls

A handle method (\`player:open_menu(...)\`, \`menu:set_skin(...)\`) knows whose code called it
from the innermost script function on the stack. A Lua tail call (\`return player:open_menu("shop")\`)
replaces the calling function's frame, and Lua keeps no record of which function that was, so
the method sees the function that called *that* one: when a library function ends in such a
tail call and the project called it, bare names in the call resolve in the project. Write
\`local menu = player:open_menu("shop") return menu\` in a function another package calls,
or name things in full. \`nf.*\` functions don't have this edge: each script's \`nf\` knows whose
it is.
`,
}

export const articles: Article[] = [naming, spaces, packages]
