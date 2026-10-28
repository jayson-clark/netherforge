# Dialogs

A dialog is a screen of text, questions and buttons, using Minecraft's dialog
screens (1.21.6+). It lives at `dialogs/<id>/dialog.json`.

A dialog holds nothing: what a player typed arrives with the button press and
is gone once handled. So there's one of each dialog, shown to anyone, and
every button runs Lua with the answers in hand. A button is never a command.
The dialog's one script, beside its JSON, handles every button.

```json
{
  "$schema": "../../.netherforge/schema/dialog.schema.json",
  "type": "notice",
  "title": "<gold>Welcome!",
  "body": [{ "type": "message", "text": "Tell us what to call you.", "key": "prompt" }],
  "inputs": [{ "type": "text", "key": "nickname", "label": "Nickname", "maxLength": 16 }],
  "buttons": [{ "key": "done", "label": "Done" }],
  "script": { "file": "script.lua" }
}
```

| Key                  | Meaning                                                                                                                                                            | Default  |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------- |
| `type`               | Where the buttons go: `notice` (one), `confirmation` (yes, no), `multi_action` (any number, in columns), `dialog_list` (one per listed dialog, built by the game). | `notice` |
| `title`              | MiniMessage. Required.                                                                                                                                             |          |
| `externalTitle`      | The label when another dialog lists this one.                                                                                                                      | `title`  |
| `canCloseWithEscape` |                                                                                                                                                                    | true     |
| `afterAction`        | After a press: `close`, `none` (stay up), or `wait_for_response` (show the waiting screen).                                                                        | `close`  |
| `columns`            | Button columns, 1–8. Only for `multi_action`.                                                                                                                      | 2        |
| `body`               | Paragraphs and pictures, in order.                                                                                                                                 |          |
| `inputs`             | Questions, in order. Each answer arrives under its `key`.                                                                                                          |          |
| `buttons`            | In order. A `dialog_list` may only have one: its exit button.                                                                                                      |          |
| `dialogs`            | The dialogs a `dialog_list` offers, as [references](references.md): `shop`, or `acme:help` for a package's.                                                        |          |
| `script`             | The dialog's one Lua script: `{ "file": "script.lua", "budget": 200000 }`. See [Script](#script).                                                                  |          |
| `pauseMenu`          | Put the dialog on the pause screen. See [In the player's own menus](#in-the-players-own-menus).                                                                    | false    |
| `quickActions`       | Put the dialog on the quick actions key. Same rules as `pauseMenu`.                                                                                                | false    |

Every piece of text (titles, body text, labels, options, buttons and their
tooltips) is MiniMessage, and can show a resource pack glyph with `<glyph:<pack>/<key>>`
(see [Glyphs](resource-pack.md#glyphs)).

A dialog's script listens for presses (`this:on("press", ...)`,
`this:button(key):on("press", ...)`) and for `close`: leaving it without a
press. Minecraft reports leaving only through a dialog's exit action, so
NetherForge gives every dialog that has one an exit action of its own: a
`multi_action` dialog gets a "Back" exit button, and a `dialog_list`'s exit
button is its one button (labelled as you say, "Back" without one), so
pressing it or Escape there is a `close`, never a `press`. On a notice, Escape
is a press of its button; on a confirmation, of its second. A notice without
a button, or a confirmation with fewer than two, gets NetherForge's exit button
where one is missing ("Ok", "Yes", "No"), so pressing that or Escape is a
`close`. Use `afterAction` to say what pressing does.

## Body

- `{ "type": "message", "text": "…", "width": 200 }`: MiniMessage text.
- `{ "type": "item", "item": { … }, "description": "…", "showTooltip": true, "width": 16, "height": 16 }`:
  an [item](menu.md#items) and an optional caption.

Either can have a `key` that names it, so a script can refer to the element:
`"key": "prompt"`. Keys follow the same rule as button keys (letters, digits,
`_` and `-`, at most 64 characters), and no two body elements may share one.
A bad key is an error (`dialog.body-key`), and so is a repeated one
(`dialog.body-duplicate`).

## Inputs

Every input has a `type`, a unique `key` (letters, digits, `_`, `-`) and an
optional `label`.

| Type            | Extra keys                                                                     |
| --------------- | ------------------------------------------------------------------------------ |
| `text`          | `width`, `initial`, `maxLength`, `lines` (multi-line when > 1), `labelVisible` |
| `boolean`       | `initial`, `onTrue`, `onFalse` (what each state reads back as)                 |
| `single_option` | `width`, `options`: `[{ "id", "label", "initial" }]`                           |
| `number_range`  | `width`, `start`, `end`, `step`, `initial`                                     |

## Buttons

`{ "key": "done", "label": "Done", "tooltip": "…", "width": 150 }`.
A button has no script of its own. The dialog's script handles it with
`this:button("done"):on("press", ...)`.

## In the player's own menus

`"pauseMenu": true` lists the dialog with the buttons the game adds to the pause
screen, and `"quickActions": true` puts it on the quick actions key's dialog.
Minecraft reads the dialogs of those places from the server's dialog registry,
which it fills only while it loads, so NetherForge writes these dialogs into the
datapack it generates when the server starts. It follows that a change to one
of them (turning a flag on or off, or editing a dialog that is on a menu) needs
a restart: the dev server restarts itself, and `/nf reload` on a production
server says so. Editing a dialog that is on neither menu reloads as usual.

A registry dialog can't be built per show, so it is written once, as the file
says, with every button a custom click the server hears. The dialog's one script
works as it does for a dialog `show` opens: the same `press` and `close` events,
and the inputs' answers in `event.values`. There is no `context`, since nothing
opened it from Lua, and an opening's `values`, `title` and `body` options don't
apply: these screens are the file's. An item body keeps its kind, count, name,
glint and resource pack look, but not enchantments, lore or script data.

A `dialog_list` on a menu brings the dialogs it lists into the registry too
(they aren't on the menu themselves). A dialog made in Lua (`nf.dialogs.create`)
can't be on a menu. Every supported Minecraft version has these screens (1.21.6
and later).

## Script

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this dialog's folder.                                 | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

There's one of each dialog, so its script runs once, from load until the dialog
is reloaded. In it, `this` is the dialog (a [`Dialog`](../reference/dialog.md)).
See the [Dialogs guide](../guide/dialogs.md).

### Files beside the script

Any other `.lua` files in the dialog's folder, at any depth, belong to the
dialog. The script loads them with `require`, which looks in the script's
folder before it looks for a module: `require("answers")` is `answers.lua`
(or `answers/init.lua`). Such a file runs as part of the script, once: its
globals are the script's and `this` is the dialog. Saving, adding or deleting
one reloads the dialog, as saving the script does. Name them as module files
are named, with letters, digits and `_`, or `require` can't reach them
(`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).
