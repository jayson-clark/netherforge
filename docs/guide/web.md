# Web services

Scripts can talk to the web without a separate service beside the server: send
a request and read the answer, follow a live feed, hold a WebSocket open, or
[answer requests](#answering-requests) from the web: webhooks, logins, small
pages.
Everything goes to hosts the package declares, so whoever runs the server sees
every site the project talks to (`/nf requires`), and the owner limits how much
of it there may be in [`config.yml`](./server-config.md#web-requests).

## Declaring the hosts

List each host a package's scripts reach in its `netherforge.json`. One list
covers every kind of connection (`http`, `https`, `ws` and `wss`), and `*.`
covers every name under a domain:

```json
{
  "requires": {
    "http": ["discord.com", "gateway.discord.gg", "*.example.com"]
  }
}
```

Write hosts, not URLs: `"wss://gateway.discord.gg"` is a problem
(`project.requires-host`) that tells you which host to write. A call to a host
the package didn't declare is an error at the line that made it, and so is one
made by a library on behalf of a project that declared it: each package
declares its own.

## One request

[`nf.http.request`](../reference/nf.http.md#request) sends a request and gives
the whole response. Call it in a task and it waits; or pass a callback.

```lua
nf.task(function()
  local response, err = nf.http.request({ url = "https://api.example.com/status" })
  if not response then
    log("couldn't ask: " .. err)
  elseif response.status == 200 then
    local status = nf.json.decode(response.body)
    nf.server.broadcast("<gray>Servers online: " .. status.online)
  end
end)
```

## A live feed

Some services keep the response open and send more as it happens: a feed of
lines (NDJSON), Server-Sent Events, or plain chunks.
[`nf.http.stream`](../reference/nf.http.md#stream) hands each piece to your
script as a `message` event, on the main thread, in order:

```lua
local feed = nf.http.stream({
  url = "https://scores.example.com/live",
  format = "events", -- Server-Sent Events; "lines" (the default) or "chunks"
})
feed:on("open", function(event)
  if event.status ~= 200 then
    log("the feed said " .. event.status)
  end
end)
feed:on("message", function(event)
  if event.type == "goal" then
    local goal = nf.json.decode(event.data)
    nf.server.broadcast("<gold>" .. goal.team .. " scored!")
  end
end)
```

It returns at once and connects in the background. Its `open` event comes when
the response begins (an error status opens it too, so check `event.status`),
then a `message` for each line, event or chunk, and finally one `end`.

## WebSockets

A WebSocket is a connection both sides send messages over, for as long as it's
open: chat bridges, game feeds, Discord's gateway.
[`nf.http.websocket`](../reference/nf.http.md#websocket) opens one; `send`
text on it and hear each `message`:

```lua
local socket = nf.http.websocket({ url = "wss://chat.example.com/live" })

socket:on("open", function()
  socket:send(nf.json.encode({ type = "hello", server = "survival" }))
end)

socket:on("message", function(event)
  local said = nf.json.decode(event.data)
  nf.server.broadcast("<aqua>[web] " .. said.author .. ": <white>" .. said.text)
end)

nf.on("player_chat", function(event)
  socket:send(nf.json.encode({ author = event.player:name(), text = event.message }))
end)
```

What you `send` before it opens waits and goes, in order, once it does. Only
text messages are taken; a service that sends binary messages ends the socket.
A protocol that wants a heartbeat (Discord's does) is your script's to keep,
with `nf.every`.

## How connections end

A stream or WebSocket ends exactly once, with an `end` event whose `reason`
says why:

| `reason`     | What happened                                                                                                          |
| ------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `"finished"` | The other side ended it: the response is complete, or the WebSocket was closed (`code` and `detail` say how).          |
| `"closed"`   | Your script called `close()`.                                                                                          |
| `"failed"`   | It couldn't connect, was refused, or broke. `detail` says what went wrong.                                             |
| `"limit"`    | One of the server owner's limits was reached (too many open, too much data, a message too large). `detail` says which. |
| `"idle"`     | Nothing was sent or received for a while (`connections.idle-seconds`).                                                 |
| `"unloaded"` | The script that opened it unloaded: a reload, or the server stopping.                                                  |

Connections belong to the script that opened them, like its timers and
handlers, so a reload never leaves one behind: the old script's connections
close, and the new script opens its own.

### Reconnecting

Nothing reconnects by itself, because only your script knows what reconnecting
means for the service (Discord resumes a session; a feed asks for what it
missed with `Last-Event-ID`). Open it again from `end`, waiting longer after
each failure so a service that's down isn't hammered:

```lua
local wait = 20 -- ticks

local function connect()
  local socket = nf.http.websocket({ url = "wss://chat.example.com/live" })
  socket:on("open", function()
    wait = 20
  end)
  socket:on("end", function(event)
    if event.reason == "unloaded" or event.reason == "closed" then
      return
    end
    log("chat bridge ended (" .. event.reason .. "), trying again in " .. wait / 20 .. "s")
    nf.after(wait, connect)
    wait = math.min(wait * 2, 20 * 60 * 5)
  end)
end

connect()
```

Every new connection counts against the package's requests a minute, so a loop
that reconnects at once would soon end `"limit"`.

### When messages come faster than scripts take them

Messages are handed to scripts a limited number a tick per connection
(`connections.messages-per-tick`), so one busy feed can't take a whole tick.
Those waiting are kept, up to `connections.max-queued-messages`; past that the
server simply stops reading from the network until your script catches up. No
message is ever dropped: the service is slowed down instead, and one that
won't wait closes the connection itself (`"failed"`).

## Answering requests

A package that declares `"requires": { "web": true }` can answer web requests
with [`nf.web.route`](../reference/nf.web.md#route). Its routes live under its
own path, `/<namespace>/`, so in package `shop` this answers `GET /shop/stats`:

```lua
nf.web.route("GET", "/stats", function(request, response)
  response:json(200, { online = #nf.players.online() })
end)
```

The server owner has to turn web routes on (`web.enabled` in
[`config.yml`](./server-config.md#web-routes)); until then, the package has a
warning and nothing is served. The handler runs on the main thread, under the
script's budget, like any handler. It gets the `request` (its `method`,
`path`, `params`, `query`, `headers`, `body`, `form` and `ip`) and a
`response` to answer with `send`, `json` or `redirect`.

**Path parameters.** A segment `:name` matches any one segment, and a last
`*name` matches the rest of the path:

```lua
nf.web.route("GET", "/replays/:id", function(request, response)
  local replay = replays[request.params.id]
  if not replay then
    response:send(404, "No such replay")
    return
  end
  response:send(200, render(replay), { ["content-type"] = "text/html; charset=utf-8" })
end)
```

**Answering later.** A handler doesn't have to answer before it returns. It
can start a task or a callback (a database query, a request of its own) and
answer from there. One that hasn't answered after `web.handler-timeout-seconds`
is answered `504`.

```lua
nf.web.route("GET", "/top", function(request, response)
  nf.db():query("SELECT name, score FROM scores ORDER BY score DESC LIMIT 10", {}, function(rows, err)
    if not rows then
      response:send(500, err)
    else
      response:json(200, rows)
    end
  end)
end)
```

**Links.** [`nf.web.url`](../reference/nf.web.md#url) builds the address the
world reaches a path at, from the owner's `web.public-url`:

```lua
local link = nf.web.url("/replays/" .. id)
if link then
  player:send_message("<click:open_url:" .. link .. "><u>Watch the replay</u></click>")
end
```

### Webhooks

Services that send webhooks sign them with a secret you share. Check the
signature with [`nf.web.verify_signature`](../reference/nf.web.md#verify_signature)
before you act on one. It compares in constant time, so a forger learns
nothing from how long it takes. Keep the secret in a
[setting](../reference/nf.md#config), not in the script:

```lua
nf.web.route("POST", "/hooks/github", function(request, response)
  local signature = request.headers["x-hub-signature-256"] or ""
  if not nf.web.verify_signature(request.body, signature, { secret = nf.config("github_secret"), prefix = "sha256=" }) then
    response:send(401)
    return
  end
  local push = nf.json.decode(request.body)
  nf.server.broadcast("<gray>New commit: " .. push.head_commit.message)
  response:send(204)
end)
```

### "Log in with…"

[`nf.web.oauth`](../reference/nf.web.md#oauth) runs an OAuth 2 login with
PKCE, so a player can link an account from another service. NetherForge keeps
the parts that are easy to get wrong: a random state tied to the player, the
code verifier, single use, a 10-minute expiry, and trading the code for a
token on the server. Register `nf.web.url("/oauth/<name>/callback")` with the
service as the redirect URI, and declare the token endpoint's host in
`requires.http`:

```lua
local discord = nf.web.oauth({
  name = "discord",
  authorize_url = "https://discord.com/oauth2/authorize",
  token_url = "https://discord.com/api/oauth2/token",
  client_id = nf.config("discord_client_id"),
  client_secret = nf.config("discord_client_secret"),
  scopes = { "identify" },
})

discord:on("complete", function(event)
  -- event.access_token calls the service as them (with nf.http.request)
  event.player:send_message("<green>Linked!")
end)

discord:on("fail", function(event)
  event.player:send_message("<red>Linking failed: " .. event.error)
end)

nf.commands.register("link", function(event)
  local url = event.player and discord:begin(event.player)
  if url then
    event.player:send_message("<click:open_url:" .. url .. "><u>Click to link Discord</u></click>")
  end
end)
```

Logins in progress are kept in memory, so a reload forgets them, and the player
starts again.

## Testing

In a test (`netherforge test`), nothing reaches the network. Each connection a
script opens is held by the test, which plays the other side:

```lua
local bridge = require("chat_bridge")

nf.test.case("chat from the web is broadcast", function()
  local socket = nf.test.connections("wss://chat.example.com")[1]
  nf.test.accept(socket)
  nf.test.receive(socket, '{"author":"Sam","text":"hi"}')
  nf.test.advance(1)
  -- ...check what players were sent...
  nf.test.disconnect(socket, { code = 1001, reason = "going away" })
  nf.test.advance(1)
end)
```

[`nf.test.connections`](../reference/nf.test.md#connections) lists them,
`accept` answers one (a stream with a status), `receive` sends text from the
far end (a stream splits it into lines or events just as a server's response
would be), `sent` lists what a WebSocket's script sent, and `disconnect` ends
one, cleanly or with an error. What the test does reaches the scripts at the
next tick, so advance after it. The host must still be declared, and the
owner's default limits apply, so a test catches those mistakes too.

Requests to your routes are made with
[`nf.test.request`](../reference/nf.test.md#request), which works whether or
not web routes are on. A handler that answers later is waited for, with the
server running on:

```lua
nf.test.case("a store webhook is acknowledged", function()
  local answer = nf.test.request("POST", "/shop/hooks/store", { body = '{"order":7}' })
  assert(answer.status == 204, answer.body)
end)
```

A login can be played from start to finish. In a test the token endpoint isn't
called; [`nf.test.oauth_token`](../reference/nf.test.md#oauth_token) sets
what it answers:

```lua
local alex = nf.test.player("Alex")
local state = discord:begin(alex):match("state=([^&]+)")
nf.test.oauth_token(discord, { access_token = "abc" })
nf.test.request("GET", "/shop/oauth/discord/callback?code=x&state=" .. state)
```
