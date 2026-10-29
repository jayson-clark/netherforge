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
  local moved, err = nf.network.send_player(player, "arena", { team = "red", kit = "archer" })
  if not moved then
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
switch is heard as `network_player_switch`.

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

`network_publish`, `network_answer` (how another server answers requests),
`network_request` (another server asks this one), `network_write` (another
server writes a map), `network_join` and `network_quit` cover the rest; see
[`nf.test`](../reference/nf.test.md#server-networks). To try it with real
servers and players moving between them, run a
[dev network](#a-dev-network-in-the-editor).

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

- **Joining** takes the hub's secret, proved per connection without sending
  it, and a server name the proxy has. Keep the hub's port to your servers.
- **The traffic** between servers and hub is encrypted and authenticated
  (AES-256-GCM, with keys derived from the secret for each connection), so it
  can't be read or changed on the way, and a server only talks to a hub that
  holds the same secret. Strangers that connect without saying hello are
  limited (64 at once, 8 from one address) and dropped after 10 seconds.
- **Packages** only reach the network if they declare it, and only their own
  channels and maps (and those of packages they depend on).
- **The hub** checks every value's size and every package's share of the
  maps, and drops a server that stops reading what it's sent.
- **Every server is trusted** once it's in: a server can publish on any
  channel and write any map. The network is one owner's servers. Don't give
  the secret to a server you don't run.
