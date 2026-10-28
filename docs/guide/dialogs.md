# Dialogs

A dialog is a screen of text, questions and buttons, built on Minecraft's
dialog screens (Minecraft 1.21.6 and newer). Use one for a welcome screen, a
form, a confirmation, or a menu of choices. It lives in `dialogs/<id>/`, as a
`dialog.json` with its one script beside it. The [dialog format](../format/dialog.md)
has every key.

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

## Kinds of dialog

`type` decides where the buttons go:

| `type`         | Buttons                                                                             |
| -------------- | ----------------------------------------------------------------------------------- |
| `notice`       | one                                                                                 |
| `confirmation` | two: yes and no                                                                     |
| `multi_action` | any number, in `columns` (1–8, default 2)                                           |
| `dialog_list`  | one per dialog listed in `dialogs`, built by the game, plus an optional exit button |

## Body and inputs

The **body** is paragraphs of MiniMessage text and pictures of items, in
order. Titles, text, labels and buttons can all use
[glyphs](resource-packs.md#glyphs-pictures-in-text) with `<glyph:ui/coin>`. A body element can
have a `key` that names it (`"key": "prompt"`), so a script can refer to it.
**Inputs** are questions, each with a unique `key`:

| Input           | Is                                                |
| --------------- | ------------------------------------------------- |
| `text`          | a text box (multi-line with `lines` > 1)          |
| `boolean`       | a checkbox                                        |
| `single_option` | a choice from a list of `options`                 |
| `number_range`  | a slider from `start` to `end` in steps of `step` |

## Showing one

```lua
-- In a centity's script: clicking it shows the welcome dialog.
this:on("click", function(event)
  nf.dialogs.get("welcome"):open_for(event.player)
end)
```

`nf.dialogs.get(id):open_for(player)` and `player:open_dialog(id)` do the same thing.
The dialog is built fresh each time it's shown, so the player sees it as the
file says now. `player:close_dialog()` takes it away.

Both take options for this one opening; the file doesn't change:

| Option    | What it does                                                                                  |
| --------- | --------------------------------------------------------------------------------------------- |
| `context` | anything at all, handed back as `event.context` on every press and on `close` of this opening |
| `values`  | what inputs start at, by input key: `{ nickname = player:name() }`                            |
| `title`   | the title for this opening                                                                    |
| `body`    | body text by the body element's `key`: a message's text, or an item's description             |

```lua
player:open_dialog("rename", {
  context = crate,                      -- the centity being renamed
  values = { name = crate:data().name },
  body = { intro = "Renaming the crate by the door" },
})
```

The context stays on the server, so it can be a handle or a function. An
input or body element the dialog doesn't have, or a value of the wrong kind
for its input (a string for a slider), is an error.

## No state, no commands

A dialog holds nothing. What a player typed arrives with the button press,
keyed by each input's `key`, and is gone once handled (along with the
opening's `context`, once the dialog leaves their screen). So there's one of each
dialog, shown to anyone, and a button never runs a command: it runs Lua with
the answers in hand.

## The script

A dialog has one script, named by the top-level `script` in `dialog.json`.
Buttons have none: the script handles them all. (A long script can `require`
more files beside it, which run as part of it: see
[`require`](scripting.md#require).) In it, `this` is the dialog (a
[Dialog](../reference/dialog.md)), and `this:button(key)` is one of its
buttons (a [Button](../reference/button.md)):

```lua
-- dialogs/welcome/script.lua
local this = this --[[@as Dialog]]

this:button("done"):on("press", function(event)
  event.player:send_message("<green>Nice to meet you, " .. nf.text.escape(tostring(event.values.nickname)) .. "!")
end)
```

The first line tells lua-language-server which `this` it is (see
[External editors](external-editors.md#which-this)). The editor writes it into
every script it creates. `this:button(key)` with a key the dialog has no
button for is an error, so a typo is caught where it was made.

The press event has the `player`, the `dialog`, the button pressed (`target`)
and its `key`, the opening's `context`, and `values`: every input's answer by its key. A text input gives the text, a
`single_option` the chosen option's `id`, a `number_range` the number, and a
`boolean` its `onTrue` or `onFalse` string (`"true"` or `"false"` unless the
file says otherwise).

A press is heard by the button's `press` handlers first, then the dialog's,
then `nf.on("dialog_press")`; `event:stop()` ends it anywhere. What a handler
returns means nothing. The script runs once for the whole server, from load
until the dialog reloads.

`this:on("close", handler)` hears a player leaving a dialog without pressing
a button: Escape, or the exit button NetherForge gives a dialog (Minecraft
reports leaving only through that), and closes a script causes, with
`player:close_dialog()` or by opening another dialog over it. A
`multi_action` dialog gets a "Back" exit button; a `dialog_list`'s own button,
if it has one, _is_ its exit button, so pressing it is a close too, not a
press. On a notice, Escape is a press of its button; on a confirmation, of its
second button. Where a notice or confirmation lacks that button, NetherForge
puts an exit button in its place, so Escape there is a close. Leaving the
server isn't a close (`player_quit` covers it). `afterAction` says what pressing a button does to the screen: `close` (the
default), `none` (stay up), or `wait_for_response` (show a waiting screen
until you show something else). `canCloseWithEscape` (default true) says
whether Escape closes it.

## Chaining dialogs

A button can show another dialog, which is how you build a multi-page form:

```lua
-- dialogs/signup/script.lua
local this = this --[[@as Dialog]]

this:button("next"):on("press", function(event)
  -- store event.values somewhere, then:
  event.player:open_dialog("signup_page_2")
end)
```

A `dialog_list` dialog does the simplest case for you: a button per listed
dialog, built by the game.

See the [Dialog reference](../reference/dialog.md) and
[DialogPressEvent](../reference/types.md#dialogpressevent).

## On the pause screen and the quick actions key

A player can open a dialog without a script showing it: Minecraft lists some
dialogs on the pause screen and on the quick actions key. Put a dialog there
with `"pauseMenu": true` or `"quickActions": true` in its `dialog.json` (or the
two checkboxes in the editor's inspector). Its script works exactly as for a
dialog you `show`: the same buttons, `press` and `close` events and
`event.values`.

The game reads those lists only as the server loads, so changing one (turning a
flag on or off, or editing a dialog that is on a menu) restarts the dev server,
and a production server says it needs a restart. See
[In the player's own menus](../format/dialog.md#in-the-players-own-menus) for
what such a dialog can't do.

## Dialogs made in a script

`nf.dialogs.create(definition)` makes a dialog from a table instead of a file,
for one that depends on what's happening. The table is what `dialog.json`
would say, in Lua spelling: keys in snake_case (`after_action`,
`can_close_with_escape`, an input's `max_length`), items as `Item` tables:

```lua
-- modules/pets/init.lua
local rename = nf.dialogs.create({
  type = "confirmation",
  title = "Rename your pet",
  inputs = { { type = "text", key = "name", label = "Name", max_length = 16 } },
  buttons = { { key = "ok", label = "Rename" }, { key = "cancel", label = "Cancel" } },
})

rename:button("ok"):on("press", function(event)
  event.player:data().pet_name = event.values.name
  event.player:send_message("Your pet is called " .. nf.text.escape(tostring(event.values.name)))
end)

nf.commands.register("rename", { players_only = true }, function(event)
  rename:open_for(event.player, { values = { name = event.player:data().pet_name or "" } })
end)
```

It's checked by the same rules as a file (a misspelled key, two buttons with
one key, a `dialog_list` naming a dialog there isn't are errors), and the
`Dialog` it returns works like any other: `open_for`, `ask` in a task,
`player:open_dialog(rename)`, and handlers on it and its buttons. It has no
script of its own (put handlers on it instead), and it lasts as long as the
script that made it.
