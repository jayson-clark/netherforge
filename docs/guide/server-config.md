# Server configuration

Everything a server owner can tune is in `plugins/NetherForge/config.yml`,
which NetherForge writes with its defaults the first time the server starts.
The editor's dev server reads the same sections, so you can try a setting
there first. Unless a section says otherwise, a change needs a server restart.

```yaml
project: 'project'

resource-pack:
  enabled: true
  port: 8163
  public-url: ''

http:
  allow-private-addresses: false

web:
  enabled: false

wasm:
  threads: 2

network:
  enabled: false

databases: {}

schedules:
  time-zone: ''

performance:
  warn-ms: 10
  warn-ticks: 20
```

## The project

`project:` is the folder holding the project's `netherforge.json` (or a
[bundle](deploying.md#projects-with-dependencies)), absolute or relative to
the server's folder. See [Deploying](deploying.md).

### Packages the server provides

A package of your own (an integration with your own systems, say) can be one
that projects on your servers depend on, while you keep control of the code
that runs. List each package's folder (the one holding its `netherforge.json`),
absolute or relative to the server's folder:

```yaml
packages:
  provided:
    - /opt/server-packages/acme_bridge
```

The server loads your copy wherever a project or bundle depends on a package of
that namespace, as long as its version is in the range the dependency takes
(else the project is refused, naming both versions). A bundle that carries its
own copy of one is refused (`runtime.provided-package`), so you learn it was
built against something else: projects build bundles for your servers without
it, by marking the dependency `"provided": true` or with
`netherforge build --exclude <namespace>`. `/nf requires` and the startup log
say which packages came from the server and from where, and a provided
package's own `requires` show there like any other's. The editor's dev server
doesn't read this: it resolves the dependency as the editor does. See
[packages the server provides](../format/packages.md#packages-the-server-provides).

## Resource packs

If the project has resource packs, NetherForge builds them into one Minecraft
resource pack and sends it to players as they join. A player only downloads it
again when it has changed. By default NetherForge serves it itself, on port
8163, so **players must be able to reach that port**.

```yaml
resource-pack:
  enabled: true
  bind: 0.0.0.0
  port: 8163
  public-url: ''
  external-url: ''
  required: false
  prompt: ''
```

| Key            | Meaning                                                                                                                                            | Default                                  |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `enabled`      | Send the resource pack to players at all. Off, it's still built, but players don't get it, so they won't see your pictures.                        | `true`                                   |
| `bind`         | The address the built-in download server listens on.                                                                                               | `0.0.0.0`                                |
| `port`         | Its port.                                                                                                                                          | `8163`                                   |
| `public-url`   | The address players download from, when that isn't the server's own: a public host name, or a reverse proxy in front of the port.                  | `http://<server-ip or localhost>:<port>` |
| `external-url` | Host it yourself (a CDN, your website): NetherForge writes `plugins/NetherForge/resource-pack.zip` whenever it changes and sends players this URL. | none                                     |
| `required`     | Disconnect players who decline it.                                                                                                                 | `false`                                  |
| `prompt`       | MiniMessage shown on the download prompt.                                                                                                          | none                                     |

The default `public-url` uses `server-ip` from `server.properties`, which is
usually empty, and then it's `localhost`: that only works for players on the
same machine, so **set `public-url` on a real server**. With `external-url`,
upload the new `resource-pack.zip` each time it changes; a player whose
download doesn't match the latest one rejects it.

**From shared storage.** With many servers, serving each pack from each
server is many open ports. Upload them to an S3-compatible store instead (AWS,
Cloudflare R2, MinIO), and players fetch them from there:

```yaml
resource-pack:
  storage:
    type: s3
    endpoint: https://<account>.r2.cloudflarestorage.com
    bucket: packs
    public-url: https://packs.example.com/ # where players fetch from: the bucket's public address or a CDN
    access-key: … # or AWS_ACCESS_KEY_ID
    secret-key: … # or AWS_SECRET_ACCESS_KEY
```

Packs are named by their hash, so servers running the same bundle share one
upload, and a pack that's in the store already isn't uploaded again. A player
is sent a pack once it's there. `prefix:` puts them under a folder of the
bucket; `netherforge publish --packs-to s3://<bucket>/<prefix>` uploads a
bundle's pack there before any server runs it.

**Across a network.** The hub remembers which pack each player's game has
loaded, so a server whose pack is the one they already have doesn't send it
again: no download, no reloading screen. That needs the proxy to keep packs as
players switch servers (`clear-resource-packs=false` in the hub's
`hub.properties`); a NetherForge server with a different pack then replaces
theirs as it sends its own (a server without NetherForge leaves the last one
on).

`/nf pack` shows the resource pack's size and the URL players are sent, and
`/nf pack send <player>` sends it again.

## Web requests

A package that declares websites under `requires.http` in its
`netherforge.json` can call them with [`nf.http.request`](../reference/nf.http.md).
They show in `/nf requires`, so you know every site the project talks to. What
those requests may do is up to you:

```yaml
http:
  allow-private-addresses: false
  allow-private: []
  max-request-bytes: 1048576
  max-response-bytes: 4194304
  timeout-seconds: 15
  requests-per-minute: 60
  connections:
    max-per-package: 4
    max-total: 32
    max-message-bytes: 1048576
    bytes-per-minute: 16777216
    idle-seconds: 300
    max-queued-messages: 1000
    messages-per-tick: 100
```

| Key                       | Meaning                                                                                          | Default |
| ------------------------- | ------------------------------------------------------------------------------------------------ | ------- |
| `allow-private-addresses` | Let requests reach this machine and your local network, not only the public internet. See below. | `false` |
| `allow-private`           | Let one package reach one host and port of yours that isn't public. See below.                   | none    |
| `max-request-bytes`       | The largest body a script may send. A larger one is an error in the script.                      | 1 MiB   |
| `max-response-bytes`      | The largest response. A larger one fails the request.                                            | 4 MiB   |
| `timeout-seconds`         | How long one request may take, redirects included.                                               | `15`    |
| `requests-per-minute`     | How many requests each package may start in a minute.                                            | `60`    |

By default a request only reaches **public addresses**, even for a website a
package declared: anything that turns out to lead to this machine, your local
network or a cloud provider's internal addresses is refused, redirects
included. Turn `allow-private-addresses` on only when a package must call a
service of your own on your network (a webhook receiver on the same machine,
say). Proxy settings from the environment are never used.

To give **one package** a service of yours without opening your network to
every other package the project pulls in, leave `allow-private-addresses` off
and list an allowance instead:

```yaml
http:
  allow-private-addresses: false
  allow-private:
    - package: acme_bridge # a package's namespace
      host: 127.0.0.1 # an address, or a name looked up when it's used
      port: 9361
```

The check is still on the address connected to: this lets `acme_bridge` reach
loopback port 9361 and nothing else that isn't public, redirects and streams
and WebSockets included, each hop checked again. It's given to the package
whose code makes the request, so another package, or the project's own
scripts, are refused the same address. The package must still declare the
host under `requires.http`. Multicast, broadcast and `0.0.0.0` stay refused
whatever you allow. `/nf requires` and the startup log list the allowances in
effect.

### Streams and WebSockets

Scripts can also hold a connection open: a live feed with
[`nf.http.stream`](../reference/nf.http.md#stream), or a chat bridge with
[`nf.http.websocket`](../reference/nf.http.md#websocket). They go only to the
same declared hosts, under the same address rules, and opening one counts as a
request against `requests-per-minute`. Every connection belongs to the script
that opened it and closes when that script reloads or the server stops.
`connections:` limits what they may hold and move:

| Key                               | Meaning                                                                                                                       | Default |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------- |
| `connections.max-per-package`     | How many connections each package may hold open at once.                                                                      | `4`     |
| `connections.max-total`           | How many all packages together may hold open.                                                                                 | `32`    |
| `connections.max-message-bytes`   | The largest line, event or WebSocket message, either way. A script sending a larger one gets an error; receiving one ends it. | 1 MiB   |
| `connections.bytes-per-minute`    | How much each package's connections may send and receive in a minute, counted on the wire.                                    | 16 MiB  |
| `connections.idle-seconds`        | A connection with nothing sent or received for this long is closed.                                                           | `300`   |
| `connections.max-queued-messages` | Messages that may wait for scripts per connection. Past it the server stops reading that connection until scripts catch up.   | `1000`  |
| `connections.messages-per-tick`   | Messages handed to scripts per connection each tick, so a busy feed can't take a whole tick.                                  | `100`   |

A connection that would go past a limit isn't an error in the script: it ends
with the reason `"limit"`, and its `end` event says which limit.

## Bots

A package that declares `"requires": { "bots": true }` can join fake players
with [`nf.bots`](../reference/nf.bots.md): opponents and teammates in games,
crowds, anything a person does that an NPC can't. They're **off until you
turn them on**, and a package that declares them has a warning
(`runtime.bots-disabled`) until you do. The editor's dev server always allows
them.

```yaml
bots:
  enabled: false
  max: 20
```

| Key       | Meaning                                               | Default |
| --------- | ----------------------------------------------------- | ------- |
| `enabled` | Let scripts join bots at all.                         | `false` |
| `max`     | How many scripts' bots may be online at once, in all. | `20`    |

A bot is a player to the server and to your other plugins: it's on the
player list, counts towards the player count, and is heard joining and
quitting. Each one is a new player every time (a fresh UUID; nothing of it is
saved, so it leaves no player data, statistics or advancements behind), it
carries the `NPC` metadata plugins check to leave fake players alone (the one
Citizens sets), and it loads only the chunks right around it. A bot leaves
when the script that joined it unloads.

## Gradual placement

`world:place_gradually` (a [build](../format/worlds.md#builds), or a big
structure) and `world:fill_gradually` (a fill too big for `fill_blocks`)
spread their work over ticks. Every one going shares a number of blocks each
tick, in turn and in equal shares, so two scripts can't double the load and one
can't starve another:

```yaml
structures:
  blocks-per-tick: 8192
  max-jobs-per-package: 4
```

| Key                    | Meaning                                                                                                       | Default |
| ---------------------- | ------------------------------------------------------------------------------------------------------------- | ------- |
| `blocks-per-tick`      | Blocks every gradual placement and fill together may set in one tick. Lower it if big builds make ticks long. | `8192`  |
| `max-jobs-per-package` | How many one package may have going at once; past it, a new one fails at once with a message naming this key. | `4`     |

A placement reads a build one part at a time, so a big build is never all in
memory. A job stops when the script that started it unloads (what it placed
stays). `/nf scripts` counts each script's under `gradual`.

## WebAssembly

A package that declares `"requires": { "wasm": true }` runs its
[WebAssembly modules](./wasm.md) (`nf.wasm`). They run off the main thread,
with no access to the server, the network or any file but their own data, and
this section holds what they may take of the machine:

```yaml
wasm:
  threads: 2
  compile: true
  max-module-mb: 64
  max-memory-mb: 256
  max-memory-total-mb: 1024
  max-call-seconds: 10
  max-queued-calls-per-package: 64
  max-instances-per-package: 8
  max-processes-per-package: 2
  max-processes-total: 4
  max-output-bytes: 1048576
  max-line-bytes: 65536
  max-queued-lines: 1000
  lines-per-tick: 100
```

| Key                            | Meaning                                                                                                                                                                   | Default   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| `threads`                      | Threads that compile modules and run every call and run, one core each at most: what all of them together can take of the CPU. Calls past them wait their turn.           | `2`       |
| `compile`                      | Whether modules are compiled into Java classes. Off, they run in the interpreter, tens of times slower, for a JVM where making classes is a problem.                      | `true`    |
| `max-module-mb`                | The largest `module.wasm` the server compiles.                                                                                                                            | `64`      |
| `max-memory-mb`                | The most memory one instance or program may grow to. A module that starts with more can't be run; one that asks for more past it is refused it (its `memory.grow` fails). | `256`     |
| `max-memory-total-mb`          | The most all instances and programs together may hold, each counted at `max-memory-mb` while it lives. Past it, a new one fails at once (`nil, err`) or ends `"limit"`.   | `1024`    |
| `max-call-seconds`             | How long one call or run may take. Past it, it's stopped, `nil, err`, and the instance is closed.                                                                         | `10`      |
| `max-queued-calls-per-package` | Calls and runs one package may have waiting or running at once; past it, a new one fails at once.                                                                         | `64`      |
| `max-instances-per-package`    | Instances one package may hold.                                                                                                                                           | `8`       |
| `max-processes-per-package`    | Programs (`nf.wasm.start`) one package may have running. Each holds a thread of its own while it runs.                                                                    | `2`       |
| `max-processes-total`          | Programs running on the server, whichever package's.                                                                                                                      | `4`       |
| `max-output-bytes`             | What a run keeps of each stream it prints to, and the most text `call_text` may answer.                                                                                   | `1048576` |
| `max-line-bytes`               | The longest line a program may be sent, and the longest it prints in one `output` event (a longer one comes in pieces).                                                   | `65536`   |
| `max-queued-lines`             | Lines a program printed that scripts haven't taken yet; past it, the program is paused until they do, so nothing is dropped.                                              | `1000`    |
| `lines-per-tick`               | Lines handed to scripts per program each tick.                                                                                                                            | `100`     |

A program runs on a thread of its own and keeps it while it waits for input,
so `max-processes-total` is also how many threads programs may hold. Modules'
threads run at the lowest priority, so where the operating system honours it
the server's own threads come first. Everything a script made of a module ends
when the script unloads. `/nf scripts` counts each script's under
`wasm instances` and `wasm programs`.

## Networks

A server behind a proxy with other servers can be part of a
[network](./network.md): its packages that declare
`"requires": { "network": true }` talk to the other servers (`nf.network`).
The proxy runs NetherForge's proxy plugin, which is the network's hub; its
`plugins/netherforge/hub.properties` has the port and the secret this section
needs:

```yaml
network:
  enabled: false
  hub: 127.0.0.1:25590
  server-name: ''
  secret: ''
  credential:
    id: ''
    secret: ''
  group: ''
  capacity: 0
  register:
    address: ''
  idle-after-seconds: 0
  wait-for-hub-seconds: 0
  require-hub: false
  messages-per-minute: 1200
  request-timeout-seconds: 10
  messages-per-tick: 1000
  max-queued-messages: 10000
```

| Key                       | Meaning                                                                                                                                                                                                                                                   | Default              |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| `enabled`                 | Whether this server joins its network.                                                                                                                                                                                                                    | `false`              |
| `hub`                     | The proxy's address and the hub's port (`port` in `hub.properties`): `host:port`, or a host alone for port 25590.                                                                                                                                         | `127.0.0.1:25590`    |
| `server-name`             | This server's name as the proxy names it (`velocity.toml`'s `[servers]`). The hub refuses a name the proxy doesn't have.                                                                                                                                  |                      |
| `secret`                  | The hub's `secret`, from `hub.properties`: the server joins as trusted. It's proved, never sent.                                                                                                                                                          |                      |
| `credential`              | This server's own credential (`id` and `secret`, from `/nfhub credentials add`), in place of `secret`: the hub holds it to its role. See [credentials](./network.md#servers-with-credentials).                                                            |                      |
| `group`                   | The group this server is an instance of (`arena`), which scripts send players to as one. See [groups](./network.md#groups-several-copies-of-one-game).                                                                                                    | none: a group of one |
| `capacity`                | How many players it takes; the hub sends a group's players only to instances with room. `0` for any number.                                                                                                                                               | `0`                  |
| `register.address`        | Where the proxy reaches this server when it isn't in `velocity.toml`: it adds itself to the proxy as it joins (`host:port`, or a port alone). `server-name` may then be `auto`. See [adding servers](./network.md#adding-servers-while-the-network-runs). | none                 |
| `idle-after-seconds`      | How long the server may be empty before the hub hears it's idle (its webhook's `server_idle`). `0`: never.                                                                                                                                                | `0`                  |
| `wait-for-hub-seconds`    | How long the server waits as it starts for the hub to welcome it (what it may do, the network's policy for it) before starting its project. `0`: it doesn't wait.                                                                                         | `0`                  |
| `require-hub`             | Run the project only once the hub has welcomed this server: one that can't reach its hub runs nothing rather than run without the network's policy, and starts by itself when the hub is there.                                                           | `false`              |
| `messages-per-minute`     | What each package may send a minute: messages, requests, map writes and players sent. Past it, `publish` and `set` return `false`, and the rest fail.                                                                                                     | `1200`               |
| `request-timeout-seconds` | How long a request waits for its answer before it fails.                                                                                                                                                                                                  | `10`                 |
| `messages-per-tick`       | What arrives that's handed to scripts each tick (messages, requests, changes, players coming and going); the rest waits for the next.                                                                                                                     | `1000`               |
| `max-queued-messages`     | What may wait for scripts; past it, the server stops reading from the hub until they catch up, so nothing is dropped.                                                                                                                                     | `10000`              |

The server logs when it joins and when it loses the hub, and dials again by
itself. `/nf network` shows how it's in its network: connected or not, every
server and how many are on it, and what it listens to. A setting that keeps it
from joining (no `server-name`, no `secret`) is said in the log and as the
warning `runtime.network-disabled` on each package that declares the network.
A dev server not put in a network is a network of its own, named `dev`, so
scripts can be tried with no proxy.

**The proxy plugin.** Put `NetherForge-<version>-velocity.jar` in Velocity's
`plugins/`. On its first start it writes `plugins/netherforge/hub.properties`
with a new secret:

| Key                                                 | Meaning                                                                                                                                                           | Default            |
| --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| `bind`, `port`                                      | Where the hub listens. Keep the port to your servers (a firewall), as you do theirs.                                                                              | `0.0.0.0`, `25590` |
| `secret`                                            | What a trusted server proves it knows. Anyone with it can join the network. Empty: only credentials join.                                                         | made for you       |
| `share.standard`, `share.isolated`                  | What the network shares with every credential of that role: `lobby:return(request), events:*(read)`.                                                              | none               |
| `group.<name>.balance`                              | How the hub picks an instance of that group to send players to: `least` or `fill`.                                                                                | `least`            |
| `group.<name>.fallback`, `group.<name>.max-waiting` | Where an instance's players go when it stops, and how many sends may wait for the group at once.                                                                  | `lobby`, `500`     |
| `fallback`                                          | Where the players of a group without its own `group.<name>.fallback` go (groups made while the network runs have none).                                           | `lobby`            |
| `webhook.url`, `webhook.secret`                     | Where the hub POSTs `server_ready`, `server_idle`, `server_gone` and `group_wanted`, signed with the secret.                                                      | none               |
| `max-value-bytes`                                   | The most one value may be as JSON: a message, a request or its answer, a map's value, a sent player's data.                                                       | `65536`            |
| `max-keys-per-package`                              | Keys one package may keep across its shared maps.                                                                                                                 | `10000`            |
| `max-bytes-per-package`                             | Bytes of values one package may keep across its shared maps.                                                                                                      | `16777216`         |
| `request-timeout-seconds`                           | How long the hub waits for a server to answer a request.                                                                                                          | `30`               |
| `max-queued-frames`                                 | What may wait to be sent to one server; past it, the server is too slow and the hub drops its connection.                                                         | `50000`            |
| `unregister-after-seconds`                          | How long a server that added itself may be gone without shutting down cleanly before it comes off the proxy.                                                      | `60`               |
| `admin.bind`, `admin.port`                          | The [admin API](./network.md#the-hubs-admin-api), for services outside the servers; off without a port. Its clients are in `admin.properties` (`/nfhub admins`).  | `127.0.0.1`, off   |
| `placement-timeout-millis`                          | How long the hub waits for a service placing a group's players; past it, that service's stream is closed and the next one, or the group's balancing, places them. | `2000`             |
| `clear-resource-packs`                              | Whether the resource packs a server sent a player come off as they move to another server.                                                                        | `true`             |

**Resource packs across the network.** Velocity keeps the resource packs a
server sent a player when they move to another server, until that server
sends its own: a lobby pack that hides the HUD or replaces sprites would
follow them into a game that sends none. With `clear-resource-packs` on (the
default), the hub takes the packs the server they left sent off them as they
arrive at the next, and the next server's own arrive as they join it
(NetherForge servers send theirs then, taking anything else off first). The
cost is a loading screen on each move between servers that both have packs,
even the same one; turn it off for a network whose servers share one pack.

Shared maps are saved in `plugins/netherforge/maps.json` on the proxy, every
few seconds while they change and when it stops. Back it up with the proxy.
`/nfhub` (permission `netherforge.admin`) lists the proxy's servers, which
are connected and with which credential. Servers' own credentials are in
`plugins/netherforge/credentials.properties`, managed with
`/nfhub credentials add|revoke|list|reload` (see
[credentials](./network.md#servers-with-credentials)); keep that file as
private as `hub.properties`.

## Worlds at run time

```yaml
worlds:
  import-root: imports # relative to the server's folder; off turns importing off
  max-loaded: 0 # the most worlds loaded at once, the server's own included; 0 for any number
```

A server that hosts different worlds over its life (one player's island, then
another's) loads and unloads them as it runs instead of restarting:
`nf.worlds.import("islands/7f2c", "island_7f2c")` makes a world from a folder
a tool put under `import-root` (laid out as a [map](../format/worlds.md#maps) is),
and `world:unload` saves it and lets it go, its players moved to another
world first. Scripts set a world up in `world_load` and tidy up in
`world_unload`. Paper loads a world on the main thread, so a load is a short
pause in the tick: the log says how long each one that took 50 ms or more
took.

## A policy over what projects require

A project says what it needs (`requires` in its `netherforge.json`), and you
accept that by running it. A server that runs projects other people wrote
needs rules that apply whatever a project asks for: `policy.json` beside
`config.yml` in `plugins/NetherForge/`:

```json
{
  "requires": {
    "allow": ["network", "db", "http"],
    "deny": ["moderation", "plugins"],
    "http": ["api.example.com", "*.cdn.example.com"],
    "refused": "refuse"
  },
  "limits": {
    "script-memory-mb": 256,
    "instruction-budget": 200000,
    "call-milliseconds": 500,
    "network-messages-per-minute": 600,
    "http-requests-per-minute": 30
  }
}
```

| Key                | Means                                                                                                                                                                                                                                  |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `requires.allow`   | The only kinds a project (or any package it uses) may require: `console`, `moderation`, `db`, `web`, `bots`, `wasm`, `network`, `http`, `plugins`.                                                                                     |
| `requires.deny`    | Kinds none may.                                                                                                                                                                                                                        |
| `requires.http`    | The only hosts one may declare for `http` (`*.example.com` for any under it), whatever it lists.                                                                                                                                       |
| `requires.refused` | `refuse` (a project the policy refuses doesn't load) or `degrade` (it loads, and the refused calls fail, as an undeclared requirement's do).                                                                                           |
| `limits`           | Limits that replace the server's own where they're lower: scripts' memory together, a script's instruction budget (whatever its file says), how long one call may run, and each package's network messages and HTTP requests a minute. |

The server's log, `/nf requires` and the problem `runtime.policy` say which
package requires what, and which rule of the policy refused it. A policy that
can't be read stops every project, rather than letting them all through. A
network can send each role's servers a policy too (`policy.<role>` in the
hub's `hub.properties`): the server holds its projects to both, the stricter
of each, and starts the project again when the network's changes. To have the
network's policy from the first moment, set `network.wait-for-hub-seconds`
(the server waits that long for the hub as it starts) and `network.require-hub`
(it runs nothing until the hub has welcomed it). A project's
author can check before publishing that it runs under a network's policy:

```sh
netherforge check --policy policy.json
```

## Metrics

```yaml
metrics:
  port: 9225 # off when 0
  bind: 127.0.0.1 # 0.0.0.0 for a scraper on another machine
```

The server serves its numbers for Prometheus at `/metrics`, gathered once a
second: `netherforge_tps` and `netherforge_tick_milliseconds` (how it keeps
up), `netherforge_runtime_tick_milliseconds` (NetherForge's share, average and
worst), `netherforge_players`, `netherforge_entities` and `netherforge_chunks`
by world, `netherforge_centities`, by package `netherforge_scripts`,
`netherforge_script_milliseconds` and `netherforge_script_bytes`, and
`netherforge_network_sent_total` and `netherforge_network_heard_total`, its
`netherforge_state` and `netherforge_startup_milliseconds`. Everything is
counted, nothing listed, so collecting them costs nothing a server would
notice. The hub has metrics of its own (`metrics.port` in `hub.properties`):
servers and players by group, each server's tick time, frames, what each
package keeps in maps, requests and sends waiting, admin streams and proxies.
The proxy plugin serves its own beside them, and alone when it's joined to a
hub that runs on its own (`remote.hub`), each labelled `proxy` with its
`remote.name`: `netherforge_proxy_players`, `netherforge_proxy_servers` (by
`source`, `config` or `runtime`), `netherforge_proxy_logins_total`,
`netherforge_proxy_logins_refused_total` (by `reason`: `banned`, `draining`,
`hub`, `denied`, `initial_server` when the server a login was placed on didn't
take it, `no_server` when no server was chosen for it at all)), `netherforge_proxy_moves_total`,
`netherforge_proxy_move_failures_total` (by `reason`), and with a remote hub
`netherforge_proxy_hub_connected` and `netherforge_proxy_hub_reconnects_total`.

## The control endpoint

The tool that runs the server (a container's sidecar, a host's agent) can read
its state and tell it what to do without a player or the console, through a
small HTTP endpoint that's never reachable from elsewhere:

```yaml
control:
  socket: /run/netherforge/control.sock # a Unix socket: whoever may open the file may use it
  port: 0 # or a TCP port on 127.0.0.1, with the token in token-file
  token-file: control.token # in the plugin's folder; made for you
  stall-seconds: 10 # no tick completed for this long: the server is stalled
```

| Request                                                                                                                                       | Does                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /state`                                                                                                                                  | Its [state](./network.md#a-servers-life-starting-ready-draining-stopping), its `players` (each `uuid`, `name` and `world`; `player_count`, and `player_names` as before), how long it's been empty, its project and version, its `worlds` (each `name`, `players` and, when nobody's in it, `empty_seconds`); `writes_paused` and `paused_worlds`; `last_tick` (Unix milliseconds of the last completed tick), `tick_ms` (its length) and `stalled`. |
| `GET /events`                                                                                                                                 | A stream (Server-Sent Events) of `state`, `player_join` and `player_quit` (`player`, `uuid`, `world`), `player_world` (`uuid`, `from`, `to`: a player changing worlds), `world_load`, `world_unload`, `writes` (`paused`, and `world` for one world's own pause) and `stalled` (no tick for `stall-seconds`).                                                                                                                                        |
| `GET /healthz`                                                                                                                                | 200 while ticks complete, 503 before the first and once stalled. Needs no token, for probes.                                                                                                                                                                                                                                                                                                                                                         |
| `POST /drain` `{ "draining": false }` to undo                                                                                                 | As `/nf drain`.                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `POST /shutdown` `{ "after_empty": true, "timeout": 60 }`                                                                                     | As `/nf shutdown`.                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /save` `{ "world"?, "pause": true, "timeout": 300 }`                                                                                    | Saves every world (or only `world`) and flushes it to disk; with `pause`, its writes stop (the server's `save-off`, world by world) until `POST /resume` or the timeout. A world's pause and the whole server's are kept apart: a world's writes go on when neither holds it. An unknown world is `404`.                                                                                                                                             |
| `POST /resume` `{ "world"? }`                                                                                                                 | Ends the whole server's pause (worlds paused on their own stay paused), or only `world`'s.                                                                                                                                                                                                                                                                                                                                                           |
| `POST /worlds/load` `{ "name", "folder"?, "terrain"? }`                                                                                       | Loads a saved world, or with `folder` imports one from the import root (as `nf.worlds.import`).                                                                                                                                                                                                                                                                                                                                                      |
| `POST /worlds/create` `{ "name", "terrain"?, "seed"?, "generator"?, "environment"?, "dimension_type"?, "structures"?, "keep_spawn_loaded"? }` | Makes a new world, the project's from then on (as `nf.worlds.create`): `400` for a terrain, dimension type or option that isn't one, `409` for a name in use or `max-loaded` reached.                                                                                                                                                                                                                                                                |
| `POST /worlds/unload` `{ "name", "save"?, "delete"?, "move_players_to"? }`                                                                    | Unloads one of the project's worlds, its players moved out first (as `world:unload`); a world paused on its own is saved and its pause ends.                                                                                                                                                                                                                                                                                                         |

So a tool copies a consistent world with `POST /save {"pause": true}` (or
`{"world": "island_7f2c", "pause": true}` for one world, the others carrying
on), the copy, then `POST /resume` (with the same `world`); if it never resumes, the timeout does. Every action
is in the server's log, with where it came from.

The endpoint answers from threads of its own, so a server whose main thread is
stuck (an endless script, a deadlock) still answers: that's why its health
comes from ticks. Point a liveness probe (or an Agones health sidecar) at
`/healthz`.

## Web routes

A package that declares `"requires": { "web": true }` can answer web requests
with [`nf.web`](../reference/nf.web.md): webhooks from a store or CI, "log in
with…" callbacks, small pages and JSON for a website. They're **off until you
turn them on**, and a package that declares them has a warning
(`runtime.web-disabled`) until you do. `/nf requires` lists every route and
whose it is.

```yaml
web:
  enabled: false
  public-url: ''
  trust-proxy-headers: false
  max-body-bytes: 65536
  max-response-bytes: 1048576
  requests-per-minute-per-ip: 60
  max-pending: 64
  handler-timeout-seconds: 10
```

| Key                          | Meaning                                                                                                                                                               | Default                            |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| `enabled`                    | Serve web routes at all.                                                                                                                                              | `false`                            |
| `public-url`                 | The address the world reaches them at, which scripts build links with (`https://play.example.com`).                                                                   | the resource pack server's address |
| `trust-proxy-headers`        | Take a request's address from `X-Forwarded-For`: its last entry, the one your proxy added. Only behind your own reverse proxy (one hop): anyone can send that header. | `false`                            |
| `max-body-bytes`             | The largest request body. A larger one is answered `413` without reaching a script.                                                                                   | 64 KiB                             |
| `max-response-bytes`         | The largest answer a script may send.                                                                                                                                 | 1 MiB                              |
| `requests-per-minute-per-ip` | How many requests one address may make a minute. Past it: `429`.                                                                                                      | `60`                               |
| `max-pending`                | How many requests may wait for the main thread at once. Past it: `503`. At most 32 are handled each tick, so a flood can't take over a tick.                          | `64`                               |
| `handler-timeout-seconds`    | How long a script may take to answer. Past it: `504`.                                                                                                                 | `10`                               |

Routes are served on the resource pack's port (`resource-pack.bind` and
`port`), each package under its own path (`/shop/...`). NetherForge doesn't do
HTTPS itself: put a reverse proxy in front for that, and set `public-url` to
the address it serves. With [Caddy](https://caddyserver.com), for example:

```
play.example.com {
  reverse_proxy localhost:8163
}
```

```yaml
resource-pack:
  bind: 127.0.0.1 # only the proxy reaches the port
  public-url: https://play.example.com
web:
  enabled: true
  public-url: https://play.example.com
  trust-proxy-headers: true
```

A proxy also protects against clients that send a request very slowly, which
the built-in server doesn't time out.

## Databases

A package that declares `"requires": { "db": true }` gets a SQLite database of
its own with no setup (see [Migrations](../format/migrations.md)). To let
scripts use a MySQL, MariaDB or PostgreSQL server too (a network's shared one,
say), name it here. Scripts open it by that name (`nf.db("network")`) and never
see the host, user or password.

```yaml
databases:
  network:
    type: mysql
    host: db.example.com
    database: netherforge
    username: netherforge
    password: change-me
    packages: [shop, ranks]
```

| Key             | Meaning                                                                                                      | Default                 |
| --------------- | ------------------------------------------------------------------------------------------------------------ | ----------------------- |
| `type`          | `mysql` (MariaDB too) or `postgres`.                                                                         | required                |
| `host`          | The database server's host name or address.                                                                  | `localhost`             |
| `port`          | Its port.                                                                                                    | `3306` / `5432` by type |
| `database`      | The database on that server.                                                                                 | required                |
| `username`      | The user to connect as.                                                                                      | empty                   |
| `password`      | Its password. Never logged, and never readable by a script.                                                  | empty                   |
| `pool-size`     | The most statements running at once on this connection (1 to 32).                                            | `4`                     |
| `packages`      | The namespaces of the packages allowed to use it. A package that isn't listed is refused.                    | none                    |
| `properties`    | Extra driver properties, like `sslMode: REQUIRED`.                                                           | none                    |
| `own-databases` | The namespaces of packages whose own database (`nf.db()`) lives here instead of in a SQLite file. See below. | none                    |

A package needs **two yeses** to use one of these: it asks for a database in
its own `netherforge.json`, and you list it under the connection's `packages`.
A package can never open your database on its own say-so.

Things to know:

- **Tables aren't created for you** on a connection opened by name. A
  package's `migrations/` set up its own database only. On a shared server, scripts create their own tables
  (`CREATE TABLE IF NOT EXISTS`, in that server's SQL dialect) or you manage
  them yourself. Packages share the connection, so give tables names only
  their package would pick (`shop_scores`).
- The first time the server starts with a PostgreSQL connection, Paper
  downloads its driver; that needs internet access once.
- **The database being down doesn't stop the server.** The first statement
  that needs it fails with a message saying it couldn't connect (the log says
  why), and the next one tries again.

### A package's own database on your server

To run several servers of one project against one database, move the
packages' own databases there without changing the project: the same
`nf.db()` calls and the same migrations.

```yaml
databases:
  game:
    type: postgres
    host: pooler.example
    database: game_chess
    username: game_chess
    password: change-me
    own-databases: [chess, chess_club] # these packages' nf.db() live here
```

- Each package listed gets a space of its own, named from its namespace: a
  schema on PostgreSQL (`chess`), a database beside the one you named on
  MySQL (`game_chess_chess`, since MySQL's schemas are databases). The user
  needs the rights to create it (or create it yourself). Spaces keep packages'
  tables apart by name; packages on one connection share its user, so one
  package's SQL could still name another's tables in full. Give a package you
  don't trust with another's data a connection and user of its own.
- Its migrations run there, each **once** across every server sharing the
  connection: under a lock the database holds, with what was applied kept in
  the space's `nf_migrations` table. Two servers starting together apply each
  migration once. A server whose copy of the package is older than what ran
  there refuses that package (`migration.ahead`) rather than work against
  tables it doesn't know.
- The migrations run are the package's versions for that server,
  `migrations/postgres/` or `migrations/mysql/` (see
  [Migrations](../format/migrations.md#on-a-mysql-or-postgresql-server)). A
  package without them is refused (`migration.dialect`), naming the package
  and the server type.
- A package's own database needs only its `requires.db`: `own-databases` is
  where it lives, not who may open the connection by name (`packages`).
- `/nf requires` and the startup log say where each package's own database is.
- A dev server reads this section too: leave `own-databases` out of its
  `config.yml`, and the project is written and tested on SQLite as before.

## Script files

`nf.files` keeps each package's files in a folder of its own,
`plugins/NetherForge/data/<namespace>/`, which no other package's scripts see
unless they're handed a file. Files kept from before packages had folders of
their own are moved into the project's folder the first time the server starts
with this version.

```yaml
files:
  max-megabytes-per-package: 64
  max-entries-per-package: 10000
```

A package may keep at most `max-megabytes-per-package` of files and
`max-entries-per-package` files and folders; a write past either fails (the
script gets `false`), so no package can fill the server's disk.

## Time zone for schedules

Scripts can run things at a time of day (`nf.schedule.daily("18:00", ...)`).
Those times are in one time zone for the whole server:

```yaml
schedules:
  time-zone: Europe/Paris
```

A zone name (`Europe/Paris`, `America/New_York`, `UTC`) or an offset
(`+02:00`). Empty, the default, is the server machine's own zone. Daylight
saving follows the zone's rules. `/nf schedules` lists every schedule with its
next run.

## Performance warnings

A script whose code takes more than `warn-ms` milliseconds a tick, on average
over `warn-ticks` ticks, is logged as slow, at most once a minute, with the
file and line it's slow in (see [Limits and performance](performance.md)).

`max-ms-per-tick` also caps how long one script's code may run in a single
tick, across all its timers, handlers and other call-ins. Once a script passes
it in a tick, that script's timers and `tick` handlers wait for the next tick,
and the owner is warned, so no one script can take the whole server by piling
up timers. Its other event handlers still run: an event happens once and isn't
delivered again, only a handler that runs can cancel it, and a custom event
(`nf.emit`, `centity:emit`) is a call whose emitter reads what its handlers
set. Its commands, its body and its `unload` run too. Their time still counts
towards its slow warning.

```yaml
performance:
  warn-ms: 10
  warn-ticks: 20
  max-ms-per-tick: 25
```

| Key               | Meaning                                                                                                      | Default |
| ----------------- | ------------------------------------------------------------------------------------------------------------ | ------- |
| `warn-ms`         | Milliseconds a tick, on average, above which a script is slow. 0 turns it off.                               | `10`    |
| `warn-ticks`      | How many ticks that average is over (1 to 100).                                                              | `20`    |
| `max-ms-per-tick` | Milliseconds a script's code may run in a tick before its timers and tick handlers wait for the next. 0 off. | `25`    |

## Exporting the game data

`export-game-data:` names a file (absolute, or relative to the server's
folder) the plugin writes the game's data to once the server has started:
the registries, tags, block states and collision shapes `netherforge check`
and `netherforge test` read, as the editor's dev server exports them. It needs
no project. `netherforge game-data <version>` starts a server just for this (see
[Testing](testing.md#what-it-needs)), so this is for tools that already
run one.

```yaml
export-game-data: game-data.json
```
