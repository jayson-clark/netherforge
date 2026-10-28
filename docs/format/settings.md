# Settings

**Server-owner settings** are what whoever runs a project may change without
touching its scripts: how many players a round takes, what the greeter says,
whether PvP is on. Like a plugin's `config.yml`, but declared by the project
(or a package) with a type, a default and a description, so the editor, the
in-game dialog and the scripts all agree on what each one is.

A project declares them in `netherforge.json`; scripts read them with
[`nf.config`](../reference/nf.md); the server keeps its owner's values in its
own folder, never in the project.

## Declaring settings

```json partial
{
  "namespace": "arena",
  "settings": {
    "max_players": {
      "type": "integer",
      "description": "How many players a round takes.",
      "default": 8,
      "min": 2,
      "max": 16
    },
    "pvp": {
      "type": "boolean",
      "description": "Whether players may hurt each other.",
      "default": true
    },
    "difficulty": {
      "type": "choice",
      "description": "How hard the waves are.",
      "default": "normal",
      "choices": ["easy", "normal", "hard"]
    }
  }
}
```

`settings` is an object of settings by name. A name is an id: lowercase
letters, digits and `_`, starting with a letter or digit, at most 64
characters (`project.setting-name`). Settings are kept in name order. Every
setting has:

| Key           | Meaning                                                                                                                                                                                         |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`        | What kind of value it holds: one of the types below.                                                                                                                                            |
| `description` | What it's for, in a sentence. Whoever runs the project reads it beside the value, in the editor, the dialog and `/nf settings list`; an empty one is a warning (`project.setting-description`). |
| `default`     | The value it has until the server's owner sets another. It must be one of the setting's own values (`project.setting-default`).                                                                 |

| `type`    | Value                       | Also                                                                                                                  | `nf.config` gives |
| --------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------- | ----------------- |
| `boolean` | `true` or `false`           |                                                                                                                       | a boolean         |
| `integer` | a whole number              | `min`, `max`: optional bounds, both included (`min` more than `max` is `project.setting-range`)                       | an integer        |
| `number`  | any number                  | `min`, `max`, the same                                                                                                | a float           |
| `string`  | any text                    |                                                                                                                       | a string          |
| `choice`  | one of a fixed list of text | `choices`: what it may be, in the order they're offered; none, an empty one or one twice is `project.setting-choices` | a string          |

A package's settings are its own: each package declares its own, its scripts
read only those, and a server's owner sets them per package.

## Reading them in a script

```lua
local max_players = nf.config("max_players")

nf.on("setting_changed", function(event)
  if event.setting == "max_players" then
    max_players = event.value
  end
end)
```

`nf.config(name)` is the owner's value, or the default when they haven't set
one. A name the package doesn't declare is an error.

When the owner changes a setting while the server runs, the package's
scripts find out one of two ways:

- a script that listens for [`setting_changed`](../reference/events.md) hears it
  and keeps running, so it can take the new value as it likes;
- a script that read the changed setting with `nf.config` and doesn't listen
  is **restarted**, as if its files were saved: its `unload` handlers run, then
  its body runs again and reads the new value. A module restarts with
  everything that required it.

A script that never read the setting is left alone.

## Where the values live

A server keeps its owner's values in its plugin folder, one file per package:
`plugins/NetherForge/settings/<namespace>.json`. It holds only what the owner
set, so a package's new default reaches a server whose owner never changed
that setting:

```json partial
{
  "difficulty": "hard",
  "max_players": 12
}
```

The owner changes them:

- in the editor, on the **Server-owner settings** page of the settings, for
  the dev server (the values go to the dev server's own folder);
- in game, with `/nf settings`, which opens a dialog with a form per package;
- with `/nf settings set <setting> <value>` and `/nf settings reset <setting>`
  (a package's setting is `namespace:name`; `/nf settings list` shows them all);
- by editing the file, then `/nf settings reload` (a full `/nf reload` reads it
  too).

Every change but a hand edit writes the file at once. What's written is held
to the setting's type: a value it can't have is refused with what it expects
(`a whole number from 2 to 16`). In a hand-edited file, a value the setting
can't have leaves the setting at its default (`settings.value`), a value for a
setting the package doesn't declare is kept but unused (`settings.unknown`),
and a file that isn't a JSON object leaves every setting of the package at its
default and isn't written over (`settings.file`). Each is reported on the
package's `netherforge.json` and in the server's log.
