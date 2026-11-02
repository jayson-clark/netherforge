# Server networks

A big server is usually several: a lobby, a few game servers, a creative
world, all behind one proxy that players connect to and move between. Scripts
on those servers need to talk to each other: a chat line said on one should
show on all of them, a party made in the lobby should follow its players into
a match, and the lobby should know which arenas are free.

`nf.network` is that. Every NetherForge server of the network connects to the
**hub**, which NetherForge's plugin for the proxy runs, and scripts on any
server can:

- **publish messages** on channels every server can listen to
  ([`nf.network.channel`](../reference/nf.network.md#channel));
- **ask one server** and get its answer ([`channel:request`](../reference/networkchannel.md#request));
- **share data**: maps the whole network reads and writes
  ([`nf.network.map`](../reference/nf.network.md#map)), with counters and
  claims that are safe when two servers write at once;
- **know who's online where** ([`nf.network.players`](../reference/nf.network.md#players)
  and the `network_player_*` events);
- **send a player to another server** with data that arrives with them
  ([`nf.network.send_player`](../reference/nf.network.md#send_player)).

```
                 players
                    │
             ┌──────┴──────┐
             │   Velocity  │  NetherForge's proxy plugin: the hub
             │   (proxy)   │  (presence, channels, requests, maps)
             └──┬───┬───┬──┘
       TCP ┌────┘   │   └────┐  each server's NetherForge connects
           │        │        │
       ┌───┴──┐ ┌───┴──┐ ┌───┴────┐
       │lobby │ │arena │ │creative│   Paper servers running NetherForge
       └──────┘ └──────┘ └────────┘
```

The proxy is the hub because it already knows what the network is: which
servers there are, who's on each, and how to move a player. There's nothing
else to run.

## Setting up

**1. The proxy.** Put `NetherForge-<version>-velocity.jar` in the proxy's
`plugins/` folder and start it. It writes
`plugins/netherforge/hub.properties` with a new random **secret** and starts
the hub on port 25590:

```properties
bind=0.0.0.0
port=25590
secret=3f1c…   # 64 hex characters, made for you
```

Keep that port off the internet (firewall it to your servers, as you do the
servers' own ports): the secret is what lets a server in, and anyone with it
can join the network. The secret itself never crosses the wire; a server
proves it knows it.

**2. Each server.** In `plugins/NetherForge/config.yml`:

```yaml
network:
  enabled: true
  hub: proxy.internal:25590 # the proxy's address, and the port above
  server-name: lobby # this server's name in velocity.toml's [servers]
  secret: 3f1c… # the secret from hub.properties
```

Restart the server. Its log says `Joined the network as lobby`, and on the
proxy `/nfhub` lists the servers and which are connected. On a server,
`/nf network` says the same from its side. A server whose name isn't one the
proxy has, or whose secret is wrong, is refused, and its log says why. It
keeps trying, so you can fix the proxy's side without restarting it.

**3. Each package that uses it** declares it in its `netherforge.json`:

```json
{ "requires": { "network": true } }
```

On a server that isn't in a network, such a package has the warning
`runtime.network-disabled`. Its scripts still load: `nf.network.is_connected()`
is `false`, `publish` returns `false`, and requests fail, as they do while
any server is cut off from its hub.

**On the editor's dev server** you don't need a proxy. A dev server that isn't
put in a network is a network of its own, named `dev`, with its hub inside it.
Channels and maps work there (what you publish comes back to you), and so do
`players` and its events for the players on it. Sending a player elsewhere
doesn't: there is nowhere else. For that, run a dev network.

## A dev network in the editor

The editor can run a whole network on your computer: a Velocity proxy with
NetherForge's hub, your project's server behind it, and other servers beside
it, each running a project folder of its own (a lobby project, a minigame,
or the same project again). Open the **Network** panel (View → Network, beside
the Console):

1. Tick **Run the dev server as a network**. Your project's server is named
   `main` there; rename it to what your scripts expect.
2. **Add server** for each other server: give it a name (`lobby`) and the
   project it runs, a folder relative to yours (`../lobby`), an absolute one,
   or `.` for this project. A folder you haven't trusted says so; **Trust
   folder** trusts it, since its scripts will run on your computer.
3. Press **Start**. The editor starts the proxy and every server, each with
   its own folder and world in the editor's data folder, and joins them to
   the proxy's hub. Players join the proxy on Settings' server port, as they
   joined the server before, and land on your project's server; proxy
   commands move them (`/server lobby`), and so does
   `nf.network.send_player`.

Each server's name is what scripts see as `nf.network.server_name()`, so one
project can do different things on different servers. Saving a file reloads
every server running it: your project's through the editor as always, the
others when their files change, from the editor or anything else. The panel
shows each server's state and console, and takes commands for the proxy and
each server; your project's server keeps the Console panel. Stop stops them
all.

Under each server's project, three fields run it as a production network
would. **Group** makes it an instance of a [group](#groups-several-copies-of-one-game) (two
`arena` servers running the same project, players sent to `arena` balanced
between them), **players** is its capacity, and **role** is what the hub lets
it do: `trusted` (the default) is everything; `standard` or `isolated` gives
it a credential of its own, owning its project's namespace, so you see what
the hub [refuses](#servers-with-credentials) before it's refused in production.
A workspace keeps them in its file's `network.options`.

`examples/network` is a project made to run this way: a lobby, an arena and a
market (the same project, its part picked by each server's name), busy with
bots, using every part of `nf.network`. Its README says how to start it.

The layout is kept per project on this computer, not in the project. Ports
behind the proxy are picked anew each start (nobody joins them directly),
and the servers run in offline mode behind Velocity's forwarding, so they see
players as the proxy logged them in. Turn the network off and your project's
server runs alone again, as it was.

## Channels: messages to every server

A channel is a name. What's published on it reaches every server whose scripts
listen to it, the publishing server included:

```lua
local chat = nf.network.channel("chat")

chat:on("message", function(event)
  -- event.value is what was published, event.server where.
  nf.server.broadcast("<gray>[" .. event.server .. "]</gray> " .. event.value.text)
end)

nf.on("player_chat", function(event)
  event:cancel()   -- every server shows it, this one too, when it comes back
  chat:publish({ text = nf.text.escape(event.player:name()) .. ": " .. event.message })
end)
```

- **Values** are anything a `data()` table can hold: tables, strings, numbers,
  booleans, `Vec3`, `Location` and handles. They go as JSON with the same codec
  as saved data. A `Location` carries its world by name, and a handle
  its UUID, which another server may not have. A value is at most
  `max-value-bytes` as JSON (64 KiB unless the proxy's owner says otherwise).
  More is an error at the script's line.
- **To one server**: `chat:publish(value, { server = "arena" })`.
- **Delivery** is at most once, and in order between any two servers. A server
  hears a channel while a script of it has a handler on it. It doesn't hear
  what was published before that, or while it was disconnected. To catch up
  after `network_connect`, ask a server that knows (a request) or keep the
  state in a map.
- `publish` doesn't wait. It returns `false` when the message didn't go: this
  server isn't connected, or its package went over the owner's
  `network.messages-per-minute`.

Channels belong to the package that names them: `"chat"` in the package
`shop` is `shop:chat` on the network, so two packages' channels never collide.
A package can use a channel of a package it depends on by its full name,
`nf.network.channel("chat_core:global")`.

## Requests: asking one server

A request goes to one server, and its `request` handlers answer by setting
`event.reply`:

```lua
-- On the economy server:
local balances = nf.network.channel("balances")
balances:on("request", function(event)
  event.reply = accounts.balance(event.value)   -- event.value: what was asked
end)

-- Anywhere else, in a task:
nf.task(function()
  local balance, err = nf.network.channel("balances"):request("economy", player:id())
  if err then
    player:send_message("<red>The bank isn't answering: " .. err)
  else
    player:send_message("You have " .. balance .. " coins")
  end
end)
```

`request` [waits for its answer](./modules.md#waiting), so call it in a task
(or give it a callback). It fails (`nil, err`) when that server isn't
connected, nothing there handles requests on the channel, a handler errored,
or no answer came within `network.request-timeout-seconds`. Handlers answer in
the handler: the asker is waiting, and the event ends when the handlers
return.

## Shared maps: data the whole network sees

A map is keys and values the hub keeps for the whole network, saved on the
proxy (`plugins/netherforge/maps.json`), so it outlives any server and the
proxy restarting:

```lua
local parties = nf.network.map("parties")

-- Reads are instant: each server keeps a copy.
local party = parties:get(player:id())

-- Writes go to the hub, and every server hears them.
parties:set(player:id(), { leader = leader:id() })
parties:delete(player:id())

parties:on("change", function(event)
  -- event.key, event.value (nil when deleted), event.previous,
  -- event.server (who changed it), event.cause ("set", "delete", "expired")
end)
```

- A server gets its copy when a script first gets the map, a tick or so later.
  `map:is_ready()` and its `ready` event say when. Until then `get` answers
  `nil`.
- `set` and `delete` don't wait. The change reaches every server with the
  map, this one included, once the hub has made it: `get` right after `set`
  still answers the old value until then. The `change` event says when it's
  there. When two servers set one key at once, the hub's order decides, and
  every server ends with the same value.
- **Counters** that two servers update at once need `add`, which the hub makes
  in one step and answers with the total:

  ```lua
  nf.task(function()
    local played = nf.network.map("stats"):add("games_played", 1)
  end)
  ```

- **Claims** need `insert`, which sets a key only if it has none. Of two
  servers inserting one key at once, exactly one gets `true`. Give a claim an
  expiry, so a server that stops while holding it can't keep it forever:

  ```lua
  nf.task(function()
    local mine = nf.network.map("arenas"):insert("arena_1", nf.network.server_name(), { expires = 20 * 60 })
    if mine then start_match() end
  end)
  ```

- `expires` is in ticks (20 a second) from now, counted by the hub's clock.
  When it runs out, the key is deleted and every server hears a `change` with
  cause `"expired"`.

Each package's maps together are held to the proxy owner's
`max-keys-per-package` and `max-bytes-per-package`. A write past them is
refused, and the server's log says so (`add` and `insert` fail with why).

## Who's online where

The proxy says who's online and on which server, and every server knows:

```lua
for _, player in ipairs(nf.network.players()) do
  log(player.name, "is on", player.server)
end

local friend = nf.network.find_player("Ann")   -- by name or UUID
if friend then ... end

nf.on("network_player_join", function(event) ... end)    -- joined the network
nf.on("network_player_switch", function(event) ... end)  -- event.previous → event.player.server
nf.on("network_player_quit", function(event) ... end)    -- left the network
```

`nf.network.servers()` lists the proxy's servers with how many players each
has and whether NetherForge on it is connected. `network_server_connect` and
`network_server_disconnect` say when another server comes and goes, and
`network_connect` and `network_disconnect` say when this one does.

## Sending players

```lua
nf.task(function()
  local server, err = nf.network.send_player(player, "arena", { team = "red", kit = "archer" })
  if not server then
    player:send_message("<red>Can't go: " .. err)
  end
end)
```

The proxy moves them; the data goes ahead of them, and the other server's
`player_arrive` handlers hear it once they've joined there, the tick after its
`player_join`:

```lua
nf.on("player_arrive", function(event)
  teams.join(event.player, event.data.team)
  kits.give(event.player, event.data.kit)
end)
```

`send_player` takes a player on this server, or anyone online in the network
by name or UUID: the lobby can send a player who's in another game. It fails
when they aren't online, the proxy has no such server, they're already there,
or the proxy couldn't move them (the server is down or full, a proxy plugin
cancelled it). Only players sent by a script arrive with `player_arrive`; any
switch is heard as `network_player_switch`. It gives the server they're on
their way to.

## Groups: several copies of one game

A network that runs several copies of a game (three arena servers) makes each
an **instance** of a **group**: in each one's `config.yml`, `network.group`
names the group and `network.capacity` how many players it takes.

```yaml
network:
  server-name: arena-3
  group: arena
  capacity: 16
```

Scripts then send players, publish and ask by group, and the hub picks the
instance:

```lua
-- To an arena with room; the party lands together on one instance.
nf.network.send_player(player, { group = "arena", data = { team = "red" } })
nf.network.send_players(party.members, { group = "arena", data = { party = party.id } })

-- To every instance, or to one of them.
nf.network.channel("events"):publish({ kind = "restart" }, { group = "arena" })
local status = nf.network.channel("status"):request({ group = "arena" }, "?")
```

Which instance a send goes to is the group's **balancing**: `least` (the
instance with the fewest players that has room, the default) or `fill` (the
fullest that still has room, so players end up together). The network's owner
sets it per group in `hub.properties` (`group.arena.balance=fill`), and a
send can ask for the other (`{ group = "arena", balance = "fill" }`). Players
already on their way count, so two sends at once don't overfill an instance.
An instance that doesn't take players now (a match is running) says so with
`nf.network.set_accepting(false)`, and is passed over until it says `true`;
it can still be sent players by name. A request to a group goes to one
instance that answers on the channel, each in turn.

`nf.network.group()` is this server's group, and `nf.network.groups()` every
group with its instances, players and room; `nf.network.players({ group =
"arena" })` is who's playing it. A server with no group is a group of one,
named as the server is, so `{ group = "lobby" }` reaches the lobby either way.
A credential can be for a group (`<id>.group=arena` in
`credentials.properties`), putting every server that uses it in that group.

## Testing

In a [script test](./testing.md) the server is a network of its own, named
`test`, with a real hub. The test adds the other servers and plays them:

```lua
nf.test.case("chat reaches the lobby", function()
  nf.test.network_server("lobby")
  nf.test.network_received("lobby", "chat")        -- the lobby listens from now on
  local player = nf.test.player("Ann")
  nf.test.raise("player_chat", { player = player, message = "hi", format = "" })
  nf.test.advance(1)
  local said = nf.test.network_received("lobby", "chat")
  assert(said[1].value.text == "Ann: hi")
end)

nf.test.case("an arriving player gets their kit", function()
  local player = nf.test.network_send("lobby", "Ann", { kit = "archer" })
  nf.test.advance(1)
  assert(player:inventory():count_item("minecraft:bow") == 1)
end)
```

`nf.test.network_server("arena-1", { group = "arena", capacity = 8 })` adds
an instance of a group (with `state = "starting"` one that isn't ready yet,
and `nf.test.network_status` to change that, for sends that wait).
`nf.test.network_role({ role = "standard", owns = { "shop" } })` joins this
server under a role, so what it may not do fails as on a real network;
bans and mutes work in a test as on a network (its hub is a real one), and
`nf.test.network_reports()` is what scripts reported. `network_publish`, `network_answer` (how another server answers requests),
`network_request` (another server asks this one), `network_write` (another
server writes a map), `network_join` and `network_quit` cover the rest; see
[`nf.test`](../reference/nf.test.md#server-networks). To try it with real
servers and players moving between them, run a
[dev network](#a-dev-network-in-the-editor).

## Servers with credentials

The shared secret lets a server in as **trusted**: it may publish on any
channel, write any map, and send any player anywhere. That's right for a
network of your own servers. For a server running someone else's project, or
one you'd rather keep to its own business, give it a **credential** of its
own instead: an id and a secret that only it uses, with a **role** the hub
holds it to (the hub, never the server, decides).

| Role       | May                                                                                                                           |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `trusted`  | Everything, as with the shared secret.                                                                                        |
| `standard` | Its own namespaces' channels and maps, whatever the network shares with standard servers, and see everyone.                   |
| `isolated` | Only what the network shares with isolated servers. Sees only its own players, and sends them only back where they came from. |

On the proxy, add one with `/nfhub credentials add <id> [role] [namespaces]`:

```
/nfhub credentials add arena standard arena,arena_stats
```

It prints the secret once (or, with `--to-file`, writes it to
`plugins/netherforge/issued/<id>.yml` for a provisioning tool to pick up),
and keeps the credential in `plugins/netherforge/credentials.properties`. The
server joins with it instead of `secret`:

```yaml
network:
  enabled: true
  hub: proxy.internal:25590
  server-name: arena-1
  credential:
    id: arena
    secret: 9b0e…
```

A credential can be shared by every server of a kind (all your arena
servers). `/nfhub credentials list` shows them and who's connected with each,
and `/nfhub credentials revoke <id>` drops its servers at once and refuses it
from then on. To turn the shared secret off altogether, empty `secret` in
`hub.properties`.

**What a credential owns and is shared.** The namespaces it **owns** (the
packages it runs) are its whole: it reads, writes, asks and answers on their
channels and maps. Of others', it has only what the network **shares**, in
`hub.properties` for every credential of a role:

```properties
share.standard=lobby:return(request), events:announcements(read)
share.isolated=lobby:return(request)
```

and in `credentials.properties` for one alone (`arena.shares=...`). Each
share is a channel's or map's full name (or `namespace:*` for all of one),
with what it may do, joined with `+`: `read` (hear a channel's messages,
keep a copy of a map), `write` (publish on it, write the map; reading comes
with it), `request` (ask on it), and `answer` (be asked on it). A credential
can also set `presence` (whether it sees players on other servers) and
`send-to` (where it may send its players: server names, `back`, or `*`).
After editing either file by hand, `/nfhub credentials reload`; servers whose
grants changed dial again under the new ones.

**What its projects may require.** `policy.standard=policy-standard.json` in
`hub.properties` sends that [policy](./server-config.md#a-policy-over-what-projects-require)
to every server joining with a `standard` credential (and likewise for the
other roles): the server holds its projects to it, as well as to its own.

**What scripts see.** `nf.network.server_role()` says what this server may
do. A call it may not make never reaches another server: publishing, asking
or writing where it may not is an error at the script's line naming
`network.forbidden`, and sending a player it may not fails with
`network.forbidden` in `err`. A handler on a channel it may not listen to
hears nothing, and the server's log says why. A less trusted server only
sends players who are on it.

## Adding servers while the network runs

Servers in `velocity.toml` are there from the proxy's start. A network that
starts servers on demand adds them as they come up, in one of two ways (a
network uses one or the other).

**A server adds itself.** It sets where the proxy reaches it, and joins with
a credential allowed to register (or the shared secret):

```yaml
network:
  enabled: true
  hub: hub.internal:25590
  server-name: auto # or a fixed name; auto is <group>-<n>: arena-3
  group: arena
  register:
    address: 10.0.0.12:25601 # or a port alone: the address the hub sees it connect from
  credential:
    id: arena
    secret: 9b0e…
```

The proxy adds it to its servers as it joins, and takes it off when it shuts
down, or when it's been gone `unregister-after-seconds` (`hub.properties`, 60
unless you say) without coming back; a server that dials again in time keeps
its name. Names are unique: a second server registering a name in use is
refused while the first is connected, and nothing can register over a server
in `velocity.toml`. A credential only registers if it's allowed to, and only
as the names and addresses it allows, in `credentials.properties`:

```properties
arena.register=true
arena.names=arena-*
arena.addresses=10.0.0.0/8
arena.group=arena
```

**Something else adds it.** An orchestrator that starts servers (Kubernetes
with Agones, say) already knows when one is ready and where it is. The hub's
[admin API](#the-hubs-admin-api) lets it add and remove them, as a client
with the `servers:write` scope:

```sh
curl -H "Authorization: Bearer $TOKEN" -d '{"name":"arena-7","address":"10.0.0.7:25565","group":"arena","credential":"arena"}' \
  http://127.0.0.1:25591/v1/servers                      # add (again, with a new address: move)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25591/v1/servers   # list
curl -H "Authorization: Bearer $TOKEN" -X DELETE http://127.0.0.1:25591/v1/servers/arena-7   # remove
```

A server added this way joins with its plain `server-name` and no
`register`; when the API named a `credential`, only that one may join under
the name, and it's an instance of the API's `group` unless it says its own. It
stays on the proxy until the API removes it, whether it's connected or not.
Removing one disconnects it from the hub. `/nfhub` marks the servers added
either way.

## A server's life: starting, ready, draining, stopping

Every server is in one of four states, which the hub knows and
`nf.network.state()` says:

| State      | Means                                                                                     |
| ---------- | ----------------------------------------------------------------------------------------- |
| `starting` | Its project is loading, or a script holds it back. Its group doesn't send it players yet. |
| `ready`    | Taking players.                                                                           |
| `draining` | Taking no new players, even by name, while it finishes what it's doing.                   |
| `stopping` | Shutting down: its players are being sent to its group's fallback.                        |

A server is `ready` once its project has loaded and its modules have run a
tick. A script that needs longer (a world to copy, a match to set up) holds
it back:

```lua
local hold = nf.network.hold_ready()
nf.worlds.copy("arena", "match", function(world)
  hold:release() -- ready the tick after the last hold goes
end)
```

`/nf drain` (or the hub's admin API, `POST /v1/servers/<name>/drain`, or
`nf.network.set_draining(true)`) drains it; `/nf drain off` undoes it.
`/nf shutdown` drains it, sends the players left to its group's **fallback**
(`group.<name>.fallback` in `hub.properties`, or the network's `fallback` for
a group without its own, such as one made while the network runs; `lobby`
unless you say), and
stops the server. With `--after-empty` it waits for the server to empty
first, up to `--timeout` seconds (60). `network_state` tells scripts each
change. The log says how long the server took from its process starting to
`ready`, and `/nf network` and the hub's admin API show it: a number to keep
low when servers start on demand.

**Waiting for a server to start.** A send can wait for an instance with room,
rather than fail:

```lua
nf.network.send_player(player, {
  group = "arena",
  wait = 90, -- seconds
  on_wait = function(position) player:send_actionbar("Queue: #" .. position) end,
})
```

The hub queues it (first come, first served, at most `group.<name>.max-waiting`
at once), tells its **webhook** the group is wanted, and sends the player as
soon as an instance is ready with room. They stay where they are meanwhile;
without `on_wait`, they see their place on their action bar.

**The webhook.** Whatever starts and stops your servers hears what it needs
from the hub's webhook (`webhook.url` in `hub.properties`): `server_ready`,
`server_idle` (empty longer than its `network.idle-after-seconds`: stop it),
`server_gone` and `group_wanted` (sends waiting for a group with no room:
start an instance). Each is a JSON `POST` signed with `webhook.secret`
(`X-NetherForge-Signature: sha256=<hex HMAC-SHA256 of the body>`).

## The hub's admin API

Services outside the network's servers (an orchestrator, a matchmaker, a
social service, a moderation tool) see the network and act on it through the
hub's admin API: HTTP and JSON, with a stream of what happens as
[Server-Sent Events](https://developer.mozilla.org/docs/Web/API/Server-sent_events).
Turn it on in `hub.properties`, and give each service a client of its own:

```properties
admin.bind=127.0.0.1
admin.port=25591
```

```
/nfhub admins add matchmaker presence:read,players:send,placement
```

That prints the client's token once, and keeps it in
`plugins/netherforge/admin.properties`. Every request sends it as
`Authorization: Bearer <token>`, and may do only what the client's scopes
allow; `/nfhub admins revoke <id>` ends it and its streams.

| Scope              | Allows                                                                                                                                |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `servers:read`     | `GET /v1/servers` (each with its group, state, players and room), and server changes streamed.                                        |
| `servers:write`    | `POST /v1/servers`, `DELETE /v1/servers/<name>`, `POST /v1/servers/<name>/drain`.                                                     |
| `presence:read`    | `GET /v1/players`, and joins, moves and leaves streamed.                                                                              |
| `players:send`     | `POST /v1/players/<id or name>/send` `{ "server" \| "group", "balance", "data", "wait" }`.                                            |
| `players:message`  | `POST /v1/players/<id or name>/message` `{ "kind": "chat" \| "title" \| "actionbar", "text" }`, shown by the proxy wherever they are. |
| `players:kick`     | `POST /v1/players/<id or name>/kick` `{ "message" }`.                                                                                 |
| `channels:publish` | `POST /v1/channels/publish` `{ "channel": "lobby:news", "value", "server", "group" }`.                                                |
| `channels:read`    | Messages on the channels its stream listens to, and requests addressed to it.                                                         |
| `placement`        | Deciding where players sent to a group, or logging in, go.                                                                            |

**The stream.** `GET /v1/events` stays open and sends what the client's scopes
allow: `player_joined`, `player_moved`, `player_left`, `server_connected` and
`server_disconnected` (every server, as it joins or leaves the hub, with its
state, group, capacity and players), `server` (a change to a connected one),
and with `?listen=lobby:news,lobby:queue` messages on those channels. Its first
event is `hello`, with the hub's `started` time and an `instance` id that's new
on every start, so a client reconnecting knows whether the hub restarted.
`GET /v1/servers` gives each connected server's `connectedSince`. A script asks a
service as it asks a server, by its client id:

```lua
local match = nf.network.channel("match"):request({ admin = "matchmaker" }, { player = player:id() })
```

It arrives on the stream as `request`, with an `id`; the service answers with
`POST /v1/requests/<id>/reply` `{ "value": ... }` (or `{ "error": ... }`).

**Placement.** A stream opened with `?place=arena` (or `?place=*login` for
players logging in) decides where those players go. For each send, the hub
sends `place` with the players and an `id`, and the service answers
`POST /v1/placements/<id>` with one of:

- `{ "server": "arena-3" }`: they go there.
- `{ "hold": "Finding you a match…" }`: they wait where they are, seeing the
  status on their action bar (or the send's `on_wait` hears it), until the
  service answers again or the send's `wait` runs out. A player logging in
  waits on the loading screen, 30 seconds at most.
- `{ "fail": "No matches tonight" }`: the send fails with that; a login is
  turned away with it.

One stream places a group at a time, the oldest open; another opened for it
takes over when the first goes. A stream that doesn't answer within
`placement-timeout-millis` (2 seconds) is taken for gone and closed (a client
whose network vanished, as a killed pod's does, can leave its connection open
for many minutes), and the placement goes to the group's next stream; with none
left, the group's own balancing places them, so the network keeps working
without the service. A service whose stream ends should open a new one.

## Running the hub on its own

The hub runs inside the proxy plugin unless you say otherwise, and that's all
a network with one proxy needs. To restart or move the proxy without the hub
(and every server's network calls) going with it, run the hub on its own:

```sh
java -jar NetherForge-<version>-hub.jar
```

It reads `hub.properties`, `credentials.properties` and `admin.properties`
from the folder it runs in, as the proxy plugin does, and reads the credential
and admin files again within seconds whenever they change. Its command line
manages them:

```sh
java -jar NetherForge-<version>-hub.jar credentials add velocity proxy   # the proxy's own credential
java -jar NetherForge-<version>-hub.jar credentials add arena standard arena
java -jar NetherForge-<version>-hub.jar admins add matchmaker presence:read,players:send
```

The proxy then joins it as a client, with a credential whose role is
`proxy`, set in its own `plugins/netherforge/hub.properties`:

```properties
remote.hub=hub.internal:25590
remote.name=velocity-1
remote.credential.id=velocity
remote.credential.secret=…
```

Servers join the hub as before (`network.hub` is the hub's address now, not
the proxy's). The proxy tells the hub its servers and who's on them, and does
what it asks: moving players, adding servers that register, messages and
kicks. While no proxy is connected the hub has no servers and moves nobody,
and servers keep running with their network calls failing; when the proxy is
back it says who's online again, and everything picks up.

**Several proxies.** Any number of proxies can join a hub running on its own,
each with its own `remote.name`, and players come in through any of them:
the hub knows who's online through which (`player.proxy` in scripts, `proxy`
in the admin API's players), sends each player's moves, messages and kicks to
the proxy they're on, and adds a server that registers on every proxy. A
player logging in through a second proxy while online through another is
refused, or with `duplicate-login=takeover` in `hub.properties` takes over,
the old session kicked. Before restarting a proxy, drain it with the admin
API (`POST /v1/proxies/<name>/drain`): it takes no new logins, so players
come in through the others. `GET /v1/proxies` lists them with their players,
and `synced` for each: whether it has every server added at run time. A proxy
joining (a restart, a new replica) gets the hub's whole list of those servers
first, and holds logins until it has them (five seconds at most); adding a
server again at the same address fills in any proxy that lacks it.

**Shared maps in a database.** With `store.url` (a PostgreSQL JDBC URL, and
`store.user`, `store.password`), the hub keeps the network's maps there
instead of in `maps.json`, so it can restart or move to another machine
without losing them. One hub at a time uses a database.

## Moderating the network

**Bans and mutes for the whole network** are kept by the hub, with the
reason, who did it and when they run out, saved with the network's maps. A
banned player is turned away by the proxy before they reach any server; a
muted player's chat is held back by every server's NetherForge (they're told
why). From the proxy:

```
/nfhub ban Ann griefing
/nfhub mute Bea spam
/nfhub unban Ann
```

From scripts, with `"requires": { "moderation": true }` and a server allowed
to moderate (a trusted server, or a credential with `moderation=true`):

```lua
nf.task(function()
  local done, err = nf.network.ban(player, { reason = "Fly hacks", expires = nf.server.unix_time() + 7 * 86400000 })
end)
nf.network.mute("Bea", { reason = "Spam" })
if nf.network.is_banned(name) then ... end
```

The admin API has them too, with the `moderation` scope (`/v1/bans`,
`/v1/mutes`).

**Reports.** `nf.network.report(player, reason, details, by)` reports a player
to whoever moderates the network: the hub keeps the last thousand
(`/nfhub reports`, `GET /v1/reports`) and tells its webhook (`report`).

**Taking a server or group off the network.** `/nfhub disable arena cheat
found` (or the admin API's `POST /v1/servers/<name>/disable`) drains every
instance of `arena`, sends their players to the group's fallback, disconnects
them from the hub and refuses them until `/nfhub enable arena`. It's kept with
the maps, so a restart doesn't let them back.

**The audit log.** The hub writes every moderation action, every server
taken off or let back, and every change of credentials and admin clients to
`audit.log` beside `hub.properties`: one JSON line each, with when, who and
what.

## Limits

The server's owner limits what its packages send (`network:` in
[config.yml](./server-config.md#networks)): how many messages, requests, map
writes and sends each package may make a minute, how long a request waits,
and how much of what arrives is handed to scripts each tick. What arrives
beyond that waits, and past `max-queued-messages` the server stops reading
from the hub, so nothing is dropped. The proxy's owner limits the network
(`hub.properties`): how large a value may be, how much each package may keep
in maps, how long the hub waits for a request's answer, and how far behind a
server may fall before the hub drops it.

## Security

- **Joining** takes the hub's secret or a credential's, proved per connection
  without sending it, and a server name the proxy has. Keep the hub's port to
  your servers.
- **The traffic** between servers and hub is encrypted and authenticated
  (AES-256-GCM, with keys derived from the secret for each connection), so it
  can't be read or changed on the way, and a server only talks to a hub that
  holds the same secret. Strangers that connect without saying hello are
  limited (64 at once, 8 from one address) and dropped after 10 seconds.
- **Packages** only reach the network if they declare it, and only their own
  channels and maps (and those of packages they depend on).
- **The hub** checks every value's size and every package's share of the
  maps, and drops a server that stops reading what it's sent.
- **A server with the shared secret is trusted**: it can publish on any
  channel, write any map and send any player. Don't give the secret to a
  server you don't run; give it a [credential](#servers-with-credentials),
  whose role the hub enforces on every frame it sends.
