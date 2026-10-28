# Deploying to a server

Running a project on a real server takes the same plugin the dev server uses,
pointed at a copy of the project. There's no export step: the plugin reads the
project folder as it is. A project that depends on
[packages](../format/packages.md) is the one exception: it runs as a
[bundle](#projects-with-dependencies).

## What you need

- A **Paper** server for the Minecraft version your project targets (the
  `minecraft` in `netherforge.json`): 1.21.11 or any 26.x.
  [Folia](https://papermc.io/software/folia) isn't supported.
- **Java 25** or newer (Java 21 is enough for 1.21.11).
- The **plugin jar** for that version, `NetherForge-<version>-paper-<minecraft>.jar`,
  from the [releases page](https://github.com/jayson-clark/netherforge/releases/latest).
  Use the same NetherForge version as the editor you author with.

## Install

1. Put the jar in the server's `plugins/` folder.
2. Put the project on the server. A git checkout is best, because deploying
   is then a `git pull`:

   ```sh
   cd /srv/minecraft
   git clone https://example.com/you/my-server.git project
   ```

3. Start the server once. NetherForge creates `plugins/NetherForge/config.yml`
   and logs that it has no project to run.
4. Set `project:` to the folder holding `netherforge.json`, absolute or relative
   to the server's folder:

   ```yaml
   project: 'project'
   ```

5. Restart the server.

Start Java with `--enable-native-access=ALL-UNNAMED`, or Java prints a warning
about the Lua library NetherForge uses:

```sh
java --enable-native-access=ALL-UNNAMED -Xmx4G -jar paper.jar --nogui
```

If the project has resource packs, check that players can download them: see
[Resource packs](server-config.md#resource-packs). It usually needs
`public-url` set and a port opened.

## Updating content

```sh
cd /srv/minecraft/project
git pull
```

Then, in game or in the server console:

```
nf reload
```

`/nf reload` with no arguments reloads the whole project: modules restart,
centities move onto their new definitions, spawned instances stay where they
are. Give it paths to reload only some resources (`/nf reload centities/tower/`).

A few things only take effect when the server starts: a new NetherForge
version, advancements, dialogs in the pause menu, structures that generate,
and which terrain the main world has. `/nf reload` tells you when one of them
changed. A project that generates the main world needs `bukkit.yml` set up
once (see [the main world](world-generation.md#the-main-world)).

## Projects with dependencies

A project with `dependencies` in `netherforge.json` runs on a production
server as a **bundle**: one folder holding the project and every package it
depends on, exactly as `netherforge.lock` pins them. Build it on your machine
or in CI with the `netherforge` command:

```sh
node .netherforge/bin/netherforge.mjs build --out /tmp/my-server-1.0.0
```

The default folder is `build/<namespace>-<version>` inside the project. The
build refuses a project with errors, or whose `netherforge.lock` is out of
date (`netherforge lock`, or opening the project in the editor, updates it).
Copy the folder to the server and point `project:` at it. Don't edit a bundle
by hand: the plugin checks it and refuses a changed one, so build a new one
instead. See [bundles](../format/packages.md#bundles).

## What a project asks of your server

A project, and every package it depends on, declares in its
`netherforge.json` anything sensitive its scripts use: moderation (bans, the
whitelist), a database, the websites it makes requests to, talking to the
other servers of a network, and other plugins.
A package can only use what it declared. The plugin logs the whole list when
it loads the project, and `/nf requires` shows it again with who asks for
each, so you see in one place what you're agreeing to by running it. See
[requirements](../format/project.md#requirements).

**Vault and PlaceholderAPI** are the other plugins a project can use:
`nf.economy` needs Vault and an economy plugin such as EssentialsX, and
`nf.placeholders` needs PlaceholderAPI. NetherForge itself needs neither. A
project that declares one your server doesn't have shows a problem, and its
calls fail with an error saying which plugin to install.

## The `/nf` command

The `/netherforge` command (alias `/nf`) needs the `netherforge.admin`
permission, which operators have by default. Commands your modules add with
`nf.commands.register` use whatever `permission` you gave them.

| Command                              | Does                                                                                     |
| ------------------------------------ | ---------------------------------------------------------------------------------------- |
| `/nf reload [paths…]`                | Reloads the project, or only the resources you name.                                     |
| `/nf spawn <centity> [player]`       | Spawns a centity at a player (you, by default).                                          |
| `/nf list`, `/nf find <centity>`     | Lists spawned centities, nearest first.                                                  |
| `/nf tp <instance>`                  | Teleports you to one, by the start of its id.                                            |
| `/nf kill <instance\|centity\|all>`  | Removes one instance, every instance of a centity, or all of them.                       |
| `/nf modules`                        | Each module, whether it's running, and the commands it added.                            |
| `/nf scripts [all]`                  | Every running script by what it costs a tick, with its subscriptions, tasks and effects. |
| `/nf profile <seconds>`              | Records every tick for that long (1 to 600 s) and writes a report; see below.            |
| `/nf pack`, `/nf pack send [player]` | The resource pack's hash, size and URL; or sends it again to a player (you, by default). |
| `/nf data [table]`                   | What NetherForge keeps: every table and its size, or one table's first rows.             |
| `/nf data show <kind> <who>`         | One saved table as JSON: `player <name\|uuid>`, `centity <instance>`, `named <name>`.    |
| `/nf data export`                    | Writes everything NetherForge keeps into `plugins/NetherForge/exports/` as JSON.         |
| `/nf settings …`                     | The project's server-owner settings; see below.                                          |
| `/nf schedules`                      | Every live `nf.schedule`, with its next run in the server's time zone.                   |
| `/nf requires`                       | What the project and its packages declare they need, and who asks for each.              |
| `/nf network`                        | How this server is in its network: connected or not, every server and who's on it.       |

## Server-owner settings

A project can declare settings for whoever runs the server, like a plugin's
config (see [Settings](../format/settings.md)). Change them in game or from
the console; no restart or `/nf reload` needed:

| Command                              | Does                                                                                              |
| ------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `/nf settings`                       | Opens a dialog with every setting (in the console, lists them).                                   |
| `/nf settings list [namespace]`      | Each setting's value, whether it's still the default, and what it's for.                          |
| `/nf settings set <setting> <value>` | Sets one. Name a package's setting as `namespace:setting`; the project's own needs only its name. |
| `/nf settings reset <setting>`       | Puts it back to its default.                                                                      |
| `/nf settings reload`                | Reads the settings files again, after you edited one by hand.                                     |

Values are kept in `plugins/NetherForge/settings/<namespace>.json`, one file per
package, holding only what you changed; a setting you never touched follows
the project's default when it updates.

## What to back up

| Where                                | What                                                                                                                                                                                                                                                        |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `plugins/NetherForge/config.yml`     | Which project to run, and the [server configuration](server-config.md).                                                                                                                                                                                     |
| `plugins/NetherForge/data/`          | Everything scripts write with `nf.files.get`. Back it up with the world.                                                                                                                                                                                    |
| `plugins/NetherForge/netherforge.db` | Spawned centities, saved tables (`player:data()`, `centity:data()`, `nf.data(name)`), the permissions scripts granted and the worlds they created. Back it up with the world (with its `-wal` file, or stop the server first). Look inside with `/nf data`. |
| `plugins/NetherForge/settings/`      | The values you gave the project's settings. Back it up with the config.                                                                                                                                                                                     |
| `plugins/NetherForge/profiles/`      | Reports from `/nf profile`. Safe to delete.                                                                                                                                                                                                                 |

Spawned centities are also ordinary entities in the world, so they're saved
and backed up with it. The server never writes into the project folder.

## Performance

`/nf scripts` lists every running script by what it costs the server a tick
(see [Limits and performance](performance.md)), and a script that's slow on
average is logged with the file and line it's slow in.

To find out exactly where the time goes, `/nf profile 30` records the next 30
seconds: every tick, every script and every function your scripts run, timed
exactly. When it's done it writes two files into
`plugins/NetherForge/profiles/` and tells you where:
`profile-<date>-<time>.txt`, a summary to read first (the average, median and
worst tick, the parts of the tick that took longest, and the functions and
scripts taking the most time, each with its file and line), and a `.json`
beside it with every number. Recording costs a little, so only do it while
you're looking for something.

## Running more than one server

Each server runs one project. To run the same project on several servers,
give each its own checkout (or a shared read-only one) and its own
`plugins/NetherForge/` folder. Servers on one machine each need their own
`resource-pack.port`.

Servers behind one Velocity proxy can be a **network**: put NetherForge's
proxy plugin on the proxy, and point each server at it (`network:` in
`config.yml`). Their scripts then publish messages to each other, ask each
other, share data, see who's online where and send players between them. See
[Server networks](./network.md).
