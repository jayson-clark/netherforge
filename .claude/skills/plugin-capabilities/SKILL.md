---
name: plugin-capabilities
description: What a package must declare to use (requires) and everything behind those declarations - the one Requirements check, nf.http.request with its address guard and ureq, streams and WebSockets, inbound web routes and OAuth logins, WebAssembly (nf.wasm: wasmtime, its own WASI, instances, programs, limits, the read-only WASI file system, test stand-ins), server networks (nf.network: the hub on the proxy, the link, channels, requests, shared maps, presence, sending players, the local network of dev servers and tests), Vault and PlaceholderAPI, server-owner settings, and the server's half of the threat model. Read before touching format's requirements, crates/runtime/src's http/, web/, wasm/, network/, interop.rs or settings.rs, packages/network, apps/proxy, or adding any new power scripts can ask for.
---

# Plugin capabilities

Powers a package has to ask for in its `netherforge.json`, and how each is held to what was asked. Anything new that reaches the network, storage, the server or another plugin goes behind a requirement checked here.

## Declared capabilities (`requires`)

A package declares what its scripts need that a package must ask for in its
`netherforge.json`'s `requires` (format's `Requires` and `Requirement`: `moderation`, `db`, `http` hosts, `plugins`,
`web`, `wasm`, `network`, `bots`), and the room's `Grants` (`crates/runtime/src/lib.rs`) holds every call to it.

- **One check: `Grants::check(namespace, requirement, called)`.** It takes a format `Requirement` and `called` as
  the call is named in errors (`nf.http.request`, `Player:ban`). It holds the package whose code is running (the
  calling scope's namespace), never the project that depends on it, and refuses naming the call, the requirement,
  the package and what to add (`Player:ban needs moderation, which package "chat" hasn't declared: add "requires": {
"moderation": true } to its netherforge.json`). A call no package's code makes passes only when every package
  declares it. The session's policy (the owner's and the network's) refuses first: "…needs db, which the server's
  policy refuses (requires.deny lists "db")".
- **Who calls it.** The generated binding of every function whose spec has `requires` calls `Runtime::requires`
  (→ `Grants::check`) before reading its arguments, so a spec flag is all most functions need. A function that
  learns more once it has its arguments checks the narrower requirement itself: `nf.http.request` checks the host of
  its URL and of each redirect (the spec's `requires: "http"` only says some host is declared; a redirect is followed
  on a worker, held to the calling package's declaration taken on the main thread); `nf.db` needs `requires: "db"`,
  and a named connection also the owner's `packages` list (plugin-persistence); a plugin's functions `requires:
"plugin:vault"`. Whether the plugin is installed is `interop.rs`'s business (a load problem), not this check's.
- **Whoever runs the server sees the sum.** The session logs it as it starts, in one line ("Requires (by its
  packages' netherforge.json): moderation (test); http:discord.com (test, chat)"), and `/nf requires` lists it. A
  manifest change restarts the session, so it's read once per session.

## HTTP (`nf.http.request`)

`crates/runtime/src/http/api.rs` checks the call on the main thread (URL, method, headers, body size, the host
requirement, the per-package rate: `RequestRate`) and hands `fetch.rs`'s `Fetcher` the work on a `Workers` thread.
Mistakes in the call are errors at the script's line; the network, the owner's limits and a redirect's refusal are
`nil, err`. The value reaches the script at a tick boundary like every async value (`pending::deliver`).

- **ureq is the client; the guard is its resolver** (`fetch.rs`'s `Guard`). ureq asks the resolver for every
  connection it makes, an address in the URL included, so the guard resolves a name once (through the injectable
  `resolve`), judges every address with `guard.rs` (a name leading anywhere non-public is refused whole), and hands
  ureq exactly those addresses: there's no second lookup to rebind. The agent has no proxy (the environment's is
  never read), no cookies, no automatic redirects, and its own pool of kept-alive connections (a pooled connection
  is one that was judged when it was made). HTTP/1.1, rustls with the platform's trust, gzip decoded by ureq, which
  is why the size limit counts the decoded body. Don't write HTTP by hand, and don't let anything resolve a name
  behind the guard's back: that's the hole.
- **A URL is read by the WHATWG rules** (the `url` crate), as a browser reads it, so `http://127.1/` is 127.0.0.1 and
  judged as loopback. (Kotlin refused such spellings unread, because the JDK and OkHttp could each read one
  differently; here the address judged is the one connected to, whatever the spelling.)
- **The body is read in a bounded loop**, failing past `http.max-response-bytes` however its length is told
  (`Content-Length`, chunked, a gzip bomb). Headers a script sets must be printable ASCII (the API says so at the
  call).
- **Redirects are followed by the fetcher, one ureq call per hop** (`MAX_REDIRECTS`, one timeout for all): each hop
  gets the host requirement as the calling package's, the address check and a new lookup; https is never downgraded
  to http; `Authorization`, `Cookie` and `Proxy-Authorization` are dropped when the host changes; 301/302 turn a POST
  into a GET, 303 always, 307/308 repeat it.
- **The guard** (`guard.rs`) classifies an address by its bytes: loopback, private, link-local, CGNAT `100.64/10`,
  benchmarking, reserved and documentation ranges, `fc00::/7`, `fe80::/10`, site-local, 6to4/NAT64/Teredo, and
  IPv4-mapped IPv6 judged as the v4 address it holds. Multicast, broadcast and `0.0.0.0`/`::` are never reachable
  (`Kind::Never`). The owner's `http.allow-private-addresses` (`HttpConfig`, `config.yml`) allows the rest.
- **One package, one private host and port** (`http.allow-private`, `PrivateAllowance`): the API takes the owner's
  entries for the package whose code made the call (`allowances(namespace)`), and they ride with it. Each hop goes
  through an agent whose guard also lets through the addresses the allowances on that hop's port stand for (a
  literal as written, a name through `resolve` at that moment), though private or this machine's own, never a
  `Never` one: one agent (and pool) per set of allowed hosts, so a connection made under one package's allowance is
  never handed to a call without it.
- **A rate of its own per allowance**: an `allow-private` entry's `requests-per-minute` (0 none) counts that
  package's requests to that host and port in `allowance_rates` instead of the general `RequestRate`; redirects
  aren't counted again.
- **In a script test run** `nf.http.request` never reaches the network: `answered_in_test` holds it to the
  declaration, the rate and the guard's judgement of a literal (a name isn't resolved; `localhost` is loopback), then
  calls the test's `nf.test.http` handler on the main thread. No answer (nil, no handler, a handler that fails) is the
  script's `nil, err` and is kept: `nf.test.advance` raises it at the test's line, and the runner fails a test that
  ended with one unraised. A test has no `config.yml`: `nf.test.allow_private` adds a `PrivateAllowance` to the test's
  allowances, which `allowances()` adds to the owner's (streams and WebSockets too), cleared when the test ends.
- `Fetcher::close` shuts every agent down when the session stops: what's in flight fails and no socket is left open.
- Tests: `crates/runtime/tests/http.rs` (loopback servers of its own: requests, mistakes, the guard judging the address
  connected to, redirects, the owner's limits, streams, WebSockets, images; `addresses_are_judged_by_their_bytes` is
  the ranges). HTTPS against a real certificate isn't tested.

## Streams and WebSockets (`nf.http.stream`, `nf.http.websocket`)

The API checks the call as for `request` (URL scheme, credentials, headers, body, the host requirement;
`Sec-WebSocket-*` headers and bad subprotocol names refused) and hands it to `http/connections.rs`, which numbers it,
files it under the opening scope and makes the `HttpStream`/`WebSocket` handle (key: number and URL, so `url()`
answers after the end). The module's doc has the design; in short:

- **The transport only queues.** A connection holds a thread of its own while it's open (not a worker's: four held
  streams would starve every request), which talks to the main thread only through the connection's `Sink`:
  `opened`, `message`, `ended` (heard once), queued in order. The connections' pass each tick hands them to scripts,
  at most `messages-per-tick` per connection, then checks idle (`idle-seconds`) and the first answer
  (`timeout-seconds`). Nothing a transport does runs Lua. In a script test run the far end is the test's
  (`nf.test.connections`, `accept`, `receive`, `sent`, `disconnect`).
- **Backpressure, not dropping.** `Sink::message` takes a permit of the connection's room (`max-queued-messages`) and
  waits for one; the main thread gives one back per message delivered. A reader waiting there stops reading the
  socket, so TCP slows the far end. A test's far end doesn't wait: a full queue is an error at the test's line.
- **Limits end, they don't error.** Too many open (`max-per-package`, `max-total`) or the request rate (shared with
  `request`) makes the connection anyway and queues `ended("limit")` for the next tick, so a script handles every
  limit in `end`. A script's own mistake (a message over `max-message-bytes` sent, a bad close code) is an error at
  its line.
- **Bytes are counted** per package against `bytes-per-minute`: a WebSocket's on the wire (its socket is the
  runtime's own, under TLS), a stream's as its body is read (after ureq decodes a gzipped one). A WebSocket's message
  and frame are capped at `max-message-bytes` too, which is what stops a far end making the server buffer an endless
  frame.
- **Streams** are ureq on an agent of their own per hop (no pool: a socket is one connection's), following redirects
  as requests do; closing one shuts its agent down, which ends a read in progress. **WebSockets** are tungstenite
  over a socket the guard chose the address of (rustls for `wss`), read with a short timeout so the same thread
  writes what scripts send; they don't follow redirects (a 3xx is `"failed"`, saying so).
- **Splitting** (`split.rs`): lines (LF, CRLF or a lone CR), Server-Sent Events (`data:` joined, `event:` → `type`,
  `id:` kept across events, comments and `retry:` skipped, a half event at the end dropped) or decoded chunks (UTF-8
  decoded leniently, a character split between reads decoded whole). The test far end's `receive` is split the same
  way.
- **Ending**: mark ended, let the far end go (a WebSocket close frame with the script's code, `1001` for
  `"unloaded"`, `1008` for `"limit"`; a stream's agent shut down), drop what's queued, emit `end` while the handle
  still answers, then remove it. A script's `close()` hears its own `end` inside the call, as `Effect:stop` does.
  A scope's release ends its connections `"unloaded"`, and so does the session's stop.
- Tests: `crates/runtime/tests/http.rs` (`a_stream_…`, `a_websocket_…`, `a_body_is_split_into_lines_events_or_chunks`)
  and the script test runner's for `nf.test.connections`.

## Web routes (`nf.web`)

Inbound requests, off by default (`web.enabled`; `WebConfig`). `crates/runtime/src/web/`'s module doc has the
design. Two halves:

- **The listener's: `web/gate.rs`.** It's served on the resource pack's HTTP server (`packs.rs`' `PackServer`, which
  runs even when packs aren't served): `/<sha1>.zip` is the pack's, anything else goes to the gate while web routes
  are on. On the listener's thread the gate refuses what it can without the main thread (path over 2048: `414`;
  `requests-per-minute-per-ip`: `429`, swept now and then so it can't grow with every address; `Content-Length` or
  the read body over `max-body-bytes`: `413`, reading no further than the limit), then queues the call in a bounded
  queue (`max-pending`, else `503`). A request's own thread waits for its answer and writes it, never the main
  thread, so a slow client holds up nobody but itself. The client's address is the connection's, or
  `X-Forwarded-For`'s first with `trust-proxy-headers`. No TLS and no slow-client timeout of its own: the docs put a
  reverse proxy in front.
- **The session's: `web/mod.rs`.** Each tick routes at most `PER_TICK` (32) queued calls: by the first segment (the
  namespace), then the most specific matching route path (`path.rs`: literal > `:param` > `*rest`) of that method
  (`HEAD` → `GET`); `404`, or `405` with `Allow`. A script's route is called with a plain table for the request and a
  `WebResponse` handle to answer by, then or later. A failed handler is reported and answered `500`; one whose scope
  has stopped `503`; pending requests time out to `504` by ticks. Routes are scope-owned; a scope's release,
  `Route:remove` and the session's stop answer what waits on them `503`. A route is a script's or the runtime's (a
  login flow's): one table, so a script's route and a flow's can't take the same method and path. With web routes off
  routes are still kept (tests reach them), and every package declaring `requires.web` has the warning
  `runtime.web-disabled`.
- **Answers** (`web/api.rs`' `WebResponse`): status 100–599; headers held to tokens and printable ASCII, none the server
  sets; `max-response-bytes`; every answer gets `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` unless
  set; `redirect` only to `http(s)://` or a path starting with one `/`, no control characters.
- **Logins: `web/oauth.rs`.** A flow has two routes of its own, `GET /<ns>/oauth/<name>/callback` and `POST
.../confirm`. `begin` makes a random state and code verifier tied to the player, kept here (never in the URL but the
  state and the verifier's S256 challenge), used once and forgotten after ten minutes (at most 1000 a flow). The
  callback finds the login by its state (unknown is `400` with no event: forged or replayed) and **asks the browser to
  confirm which Minecraft player it signs in as**, since the login is tied to the player who began it, not to the
  browser that finishes it. Only once they confirm (a single-use token from that page) is the code traded for a token
  at the flow's token endpoint, on a worker through `nf.http`'s guarded fetcher (its host held to the package's
  `requires.http`), then `complete`/`fail` on the flow and an escaped HTML page. In a test run the endpoint isn't
  called: `nf.test.oauth_token` (or a default token) answers.
- `nf.web.verify_signature`: HMAC (`encoding::hmac`), compared in constant time; an unreadable signature is `false`.
- **Tests**: `crates/runtime/tests/web.rs` (real HTTP to the listener, ticking while it waits: routing, the gate's
  refusals, a handler answered `504`, signatures, routes off, a whole login up to its token request); the script test
  runner's for `nf.test.request` (routed as the gate would, then advancing tick by tick while the answer is pending).
  The real token exchange isn't tested (it needs HTTPS with a trusted certificate).

## WebAssembly (`nf.wasm`)

`crates/runtime/src/wasm/` runs the project's modules (`wasm/<id>/`) on **wasmtime** (41: the newest on Rust 1.90),
its module docs the design. In short:

- **Compiling** (`compiled.rs`, off the main thread on the pool, as the session starts and again when a module's files
  change): validated, its imports held to WASI preview 1's functions (format's list, so `check` and the server
  agree), compiled by Cranelift; `wasm.compile: false` compiles to Pulley's bytecode for wasmtime's interpreter
  instead, and a host Cranelift can't compile for falls back to Pulley with a warning. A failure is `runtime.wasm`
  at `module.wasm`, and running the module fails saying why. One compile per load (a `Module` and its
  `InstancePre`), shared by every instance.
- **WASI is written here** (`wasi.rs`), not taken from `wasmtime-wasi`: what a module gets is exactly what the
  runtime chose, and nothing of the host's (its directories, environment, sockets) is there to be forgotten.
  Standard streams are the instance's (nothing for a library's, the given text and capped buffers for
  `nf.wasm.run`, a program's line queues for `nf.wasm.start`); arguments (`argv[0]` the module's name) and the
  environment are only what the script gave; clocks are the system's, `random_get` the OS's generator;
  `proc_exit` ends the run with its code; a blocking read or a `poll_oneoff` sleep checks the store's guard, so a
  stopped program doesn't hang there.
- **Files** (`files.rs`): descriptor 3 is the module's data at `/`, read-only, looked up in the module's data or not
  there, so no host path a module writes reaches the disk: writing, making, renaming and removing are `EROFS`, a path
  climbing out is `ENOTCAPABLE`.
- **Calls** (`calls.rs`): arguments read from the script's `args` table on the main thread and checked against the
  export's type there (a mistake is an error at the line). Numbers under Luau are doubles: a whole number in the safe
  range passes for an integer, and an `i64` result outside it comes back as a string of its digits, as a database's
  does. Strings are copied in with the module's `alloc`/`malloc` and freed with `dealloc`/`free` once the call
  returns (never after a trap or a timeout: the instance closes anyway); text comes back from a pointer and a length,
  freed once read. Each instance's calls run one at a time on its own `Lane` over the pool (`threads.rs`); a trap or
  a timeout closes the instance (its memory may be half-written), and its handle still answers `module()` until its
  scope goes.
- **Time** (`threads.rs`): one thread ticks the engine's epoch every `EPOCH`, and every store checks its `Guard` at
  each tick (wasmtime's epoch deadline callback): closed, or past `max-call-seconds`, its code traps there. That stops
  compiled code and Pulley alike, and replaces Kotlin's watchdog and `Thread.interrupt`.
- **Programs** (`program.rs`, `nf.wasm.start`): a thread each while they run (they block reading their input), bounded
  by `max-processes-total`, held to before starting. Input is a queue of lines until closed; output is cut at line
  ends (long lines into pieces of `max-line-bytes`, cut between UTF-8 characters) and queued, each line taking a
  permit of the program's room (`max-queued-lines`) and waiting for one: **backpressure, not dropping**, as for
  WebSockets. Each tick hands out at most `lines-per-tick` a program and ends one that finished. Ending from the main
  thread (stop, unload, limit) closes its input and guard, drops what's queued, and emits `end` while the handle
  answers.
- **Limits** (`WasmConfig`): memory through one `Budget` (each instance or program reserves the most it may grow to,
  `max-memory-mb`, as it starts, and gives it back when it ends, so together they can't pass `max-memory-total-mb`);
  each store's `ResourceLimiter` holds its memory to its cap. Instances per package; programs per package and in all
  (a refused one ends `"limit"` at the next tick, as connections do); queued calls per package.
- **Scope and reload**: everything is filed under its scope and goes when it's released (instances closed, programs
  `"unloaded"`). A script that ran a module restarts when the module changes (`Service::restarts`).
- **Test runs**: modules really run. `nf.test.stub_wasm` stands a test's functions in for a module (`api.rs`'
  `standing_in`, answered on the main thread at its next turn), counted against the same limits; nothing of a
  stand-in is on wasmtime's path.
- Tests: `crates/runtime/tests/wasm.rs` (real modules built from WAT: every number type, strings through memory,
  `nf.wasm.functions`, mistakes, traps, a call past its time, programs run and started, the read-only data folder,
  the owner's limits, memory caps, the requirement, a module that won't compile, restarts on change, and Pulley
  running what Cranelift does).

## Networks (`nf.network`)

Servers behind one Velocity proxy, talking through a **hub** the proxy runs. Three places:

- **`packages/network` (`:network`)**: the protocol and the hub, with nothing of a proxy or a server in it, so the
  Paper plugin, the proxy plugin and tests share one. `Protocol` (NDJSON, one JSON object a line, its kind in `"t"`:
  `HubFrame`s the hub sends, `ServerFrame`s a server sends; plain frames, not JSON-RPC, since almost everything is a
  notification and the hub relays requests under ids of its own). The handshake: the hub's `Challenge` (a nonce), the
  server's `Hello` with its name, a nonce of its own and `Auth.proof` (HMAC-SHA256 of both nonces and the name under
  the shared secret: the secret never crosses, and a proof is good for one connection and one name), then `Welcome`
  (the network as it is, and the hub's `maxValueBytes`) or `Refused`. From the `Welcome` on every line is sealed with
  the connection's `Channel` (keys from the secret and both nonces); a line that doesn't open ends the connection,
  and a hub that can't seal the welcome isn't the network's (the server says so). `Hub` is one lock over everything (sessions by name, presence, `SharedMaps`,
  request routes): frames arrive on each connection's thread, the proxy's events on its, `tick` on a timer, and
  sending only queues (`Peer.send`), so nothing waits on a socket or the proxy while holding it; the proxy is asked
  to move a player outside the lock (`handle` returns what runs after). `HubServer` is it on TCP (a reader and a
  writer thread a connection, a bounded queue that drops a server that stops reading, a read timeout servers' pings
  keep alive); `HubClient` is a client's end (connects, answers the challenge, reconnects with backoff, pings when
  idle, throws away what was queued when the connection drops: the tests' and a remote proxy's; a Paper server's is
  the Rust runtime's `network/client.rs`, which does the same); `LocalLink` is a server in the same JVM, everything
  on the calling thread. `SharedMaps` keeps values with expiries and holds each package (a map's namespace) to
  `maxKeysPerPackage`/`maxBytesPerPackage`; `FileMapStore` saves them whole (temp file, atomic move).
- **`apps/proxy/velocity` (`:proxy:velocity`, Java 25 like Velocity 4)**: `NetherForgeProxy` runs the hub with
  `HubSettings` (`hub.properties`, written with `Auth.newSecret()` on first start: a hub is never open without one),
  tells it of `ServerConnectedEvent` and `DisconnectEvent` (`PostOrder.LAST`), moves players with
  `createConnectionRequest(server).connect()` (each failed status said in words), and adds `/nfhub`. The descriptor
  is a hand-written `velocity-plugin.json` (no annotation processing in a Kotlin build). A server's name must be one
  the proxy has (matched ignoring case, as Velocity does). With `clear-resource-packs` (default true), its
  `ServerConnectedEvent` handler takes off the packs whose `originalOrigin` is `DOWNSTREAM_SERVER` when the player
  had a previous server: Velocity keeps a server's packs across a switch until the next server replaces them, so a
  lobby's would follow a player into a game that sends none. Velocity holds the new backend's packets while the
  event runs, so the new server's own packs (sent as it hears the join) arrive after the removal.
- **`apps/hub` (`:hub`)**: the hub on its own, `NetherForge-<v>-hub.jar` (`StandaloneHub`: `HubSettings`, credentials
  and admin clients read again when their files change, `PostgresMapStore` for `store.url`, a command line for
  credentials and admins). Its proxy is a `RemoteProxy`: the proxy plugin, with `remote.hub` in `hub.properties`, joins
  as a client with the `proxy` role (a `proxy`-role credential or the shared secret), sends `ProxyServers`,
  `ProxyPresence` (whole, on joining), `ProxyPlayer`/`ProxyPlayerLeft` and `ProxyLogin`, and does the hub's
  `ProxyMove`, `ProxyRegister`, `ProxyMessage`, `ProxyKick`. Several proxies may join at once (each player's
  `PlayerState.proxy` says which holds them; a player logging in on a second is refused or takes over, as
  `duplicate-login` says; `/v1/proxies` lists
  them, `synced` from `RemoteProxy.has`, and drains one). A proxy joining gets `ProxySync`, every server added at run
  time, after its welcome, and holds logins until it has applied it (`SYNC_WAIT_SECONDS`); `HubProxy.fill` sends a
  server added again at the same address to the proxies that lack it); with none, the hub has no servers. `HubSettings` lives in `:network` for both.
- **Credentials and roles** (`CredentialsFile`, `credentials.properties` beside `hub.properties`; `/nfhub credentials`):
  a server proves its credential's secret instead of the shared one, and the credential's `Grants` (a role's preset:
  `trusted` everything, `standard` its `owns` namespaces and what `share.<role>` grants, `isolated` only what's shared and
  its own players, `proxy` for a remote proxy; plus `presence`, `send-to`, `moderation`) are what the hub holds
  **every frame** to: anything else is `network.forbidden` to the sender and never reaches another server. A credential
  may pin a `group` and allow registering (`register`, `names`, `addresses`). Revoking drops its servers at once. The
  shared secret is `trusted`. `nf.network.server_role()` says what the server may do. Credentials come from two
  places: the file (`Hub.setCredentials`, at start and on reload) and the admin API (`PUT`/`DELETE /v1/credentials/<id>`,
  `Hub.putCredential`/`removeCredential`, scope `credentials`, which `AdminScope.STAR` (`*`) leaves out). The API's
  are `StoredCredential`s kept in `SavedMaps.credentials` (the store's, not a `netherforge:` map: every server reads
  those) and saved at once; the hub's effective set is the file's with the API's over them (`effectiveCredentials`),
  and both paths go through `applyCredentials`, which closes sessions whose `joinedWith` changed. `CredentialEntry.problem`
  is the one validation for both. Saves are numbered (`Snapshot`) so the timer's and the API's never write an older
  one over a newer. Secrets never go into logs, the audit line, answers or a parse error (`body(quote = false)`).
- **Groups** (`network.group`, `network.capacity`; `GroupSettings` from `group.<name>.*`): instances of one game.
  Sending to a group picks an instance accepting players with room (`least` or `fill`; `send_players` keeps a party
  together); a send can wait (`wait`, `on_wait`, the hub's `max-waiting`) or fall back (`fallback`). Publishing and
  requesting to a group reach its instances.
- **Registration** (`network.register.address`, `server-name: auto` numbering `<group>-<n>`; or `HubAdmin`'s
  `POST /v1/servers`): a server not in `velocity.toml` adds itself through `HubProxy.register`, and comes off as it
  stops or after `unregister-after-seconds`.
- **Lifecycle** (`Lifecycle`: `starting`, `ready`, `draining`, `stopping`): `nf.network.hold_ready` (a `NetworkHold`)
  keeps a server `starting`; draining (`set_draining`, `/nf drain`, the admin API) stops sends to it; `/nf shutdown`
  drains, moves players and stops. `HubEvents` POSTs each change to the owner's webhook, signed. `network.wait-for-hub-
seconds` (`network::wait_for_hub`, the link's `await_welcome`) and `require-hub` hold the project until the hub
  answers (the session is refused, then restarted once it comes); `require-project` (the network service's `life`: a
  session ticking is a project running) keeps a server `starting` while its project is refused.
- **Admin messages** (`POST /v1/players/<p>/message`): the text is MiniMessage, held by the hub to `RichText`
  (clicks `run_command`, `suggest_command`, `copy_to_clipboard`, `open_url`; hovers `show_text`; the value limit:
  400 otherwise), found by a tag scan that also looks inside a hover's text; the Velocity proxy holds the component
  Adventure built to the same lists before showing it (`allowed`), so a reading that differs can only refuse more.
- **The admin API** (`HubAdmin`, `admin.properties` as `AdminClientsFile`, `AdminScope`s per client, never a server's
  credential; constant-time token compare): HTTP and JSON plus SSE `/v1/events` (first `hello` with `Hub.started`/`instance`, then `server_connected` and
  `server_disconnected` for every server as its session is welcomed and closed, beside `server` for changes); servers, players, sends, messages,
  kicks, publishing and requests answered by an admin client (`{admin=...}`), moderation, placement hooks per group and
  holding a login until one answers. The JDK's `HttpServer` gives no socket (no keepalive, no write timeout), so a
  stream whose client's network vanished looks open for ~15 minutes: a placement it doesn't answer in time closes it
  (`Hub.tick`, `AdminStream.closeLocked`) and passes its asks to the group's next stream, then to the group's balancing.
- **Moderation** (`Moderation`: the hub's own maps `netherforge:bans`/`mutes`, outside every package's limits, saved
  with the rest; reports): `nf.network.ban`/`mute`/... need the credential's `moderation` (and `requires.moderation`);
  the proxy refuses a banned login; the runtime's chat event (`events.rs`) holds back a muted player's chat; `/nfhub disable` takes a server
  or group off. The proxy writes moderation, credential and admin changes to `AuditLog` (`audit.log`).
- **Metrics** (`crates/runtime/src/metrics.rs`, `metrics:` in `config.yml`, gathered on the main thread once a second
  and served from a thread of its own; and the hub's `metrics.port`, through `Prometheus`): Prometheus's text format at `/metrics`, off without a port. The proxy plugin's `ProxyMetrics`
  (logins and refusals by reason, moves and failures, its link to a remote hub, labelled with `remote.name`) is served
  on the same port whether or not it runs the hub, the hub's appended when it does.
- **The control endpoint's worlds** (`crates/runtime/src/control.rs`): `/save` and `/resume` take a `world` (its own
  pause, kept apart from the whole server's: a world saves again only when neither holds it; an unload ends it),
  `/worlds/create` makes a managed world (400 for a terrain or option that isn't one, 409 refused, 404 missing), and
  the snapshot keeps players by UUID with their world (`player_world` when it changes) and each world's players and
  `empty_seconds`.
- **Outside the network but beside it**: the control endpoint (`control.rs`), a Unix socket or loopback port with a
  token file, for whoever runs the server (`/state` with `running` and `refused`, `last_tick`/`tick_ms` kept every tick
  and read off the main thread, `/healthz` without a token, a watchdog thread's `stalled` after `stall-seconds`,
  `/events`, `/drain`, `/shutdown`, `/save` with pause, `/resume`, `/worlds/load|unload`); `policy.json` (format's
  `Policy`, `runtime.policy`, also sent by the hub as `policy.<role>`) allows and denies requirement kinds and lowers
  limits (`limits_under_policy`, `config.rs`); bundles are signed (`bundles.require-signature`/`trusted-keys`,
  `project_files.rs`) and fetched by URL and hash (`bundle_fetch.rs`); packs can live in S3-compatible storage
  (`pack_storage.rs`) and aren't sent again to a player who has them; a bundle may carry its pack, built.
- **The editor's dev networks**: a dev server started with `-Dnetherforge.network.hub` (and `.server`, `.group`,
  `.capacity`, `.credential`, and the secret in `NETHERFORGE_NETWORK_SECRET`, never on the command line) is in that
  network whatever `config.yml` says; the owner's limits stay (`config.rs`). A server whose dev role
  isn't `trusted` joins with a `dev-<name>` credential the editor writes to the proxy's `credentials.properties`. The editor runs the proxy and the servers (tauri-backend's "Dev
  networks"). A remote link's reader interrupted while it queues (the link stopping) drops what it was saying.
- **The runtime's `network/`** (`crates/runtime/src/network/`, its module doc the map): the link (`link.rs`, the
  plugin's life: a reload doesn't reconnect) and what the hub said (presence, servers, arrivals), applied on the main
  thread as it's drained. It's remote (`client.rs`: TCP to the proxy's hub on threads of its own, dialled again with
  a backoff; what it hears queued for the main thread with backpressure past `max-queued-messages`), local
  (`local.rs`: a dev server not in a network, named `dev`, and a script test run, `test`: the hub's rules for one
  process, frame for frame and word for word where a script sees them, this server's players the proxy's; a test's
  other servers on the same stand-in), or off (a production server not in one: the warning
  `runtime.network-disabled` on each declaring package). **The hub stays Kotlin** (`packages/network`), so
  `protocol.rs` writes its frames byte for byte as kotlinx would and `seal.rs` makes the proof and seals lines exactly
  as `Auth` and `Channel` do (`packages/network/testdata/session.json` holds both sides to it). Ids for requests,
  writes and sends are one sequence for the plugin's life, so an old session's answer never completes a new one's
  wait. The session's side (`service.rs`) drains at most `messages-per-tick` a tick, raises the events, answers
  requests (`event.reply`), keeps each map's copy (a snapshot when it starts listening, then every change), times out
  what waits, delivers arrivals the tick after the player joined, and tells the hub what it listens to whenever that
  changes. A lost connection fails every wait.
- **Names**: a channel's or map's full name is `namespace:name` (bare is the caller's package,
  `ns:name` only for its own or a package it depends on), so packages never see each other's otherwise. Handles are
  keyed by the full name and spelled back bare for their own package.
- **Limits**: the server's `network:` section (`config.rs`: per-package `messages-per-minute` through the same
  `RequestRate` as HTTP; past it `publish`/`set` are `false` and waits fail) and the hub's `hub.properties`
  (`NetworkLimits`: value size, which a server checks too, at the script's line, from the welcome's
  `maxValueBytes`; maps per package; how long the hub routes a request; how far behind a server may fall).
- **Tests**: `HubTest` (the hub through local links and once over TCP: handshake and refusals, relaying, requests and
  their failures, maps with expiry, limits and saving, presence and sending), `HubSettingsTest`, `CredentialsTest`,
  `GroupsTest`, `RegistrationTest`, `LifecycleTest`, `AdminApiTest`, `RemoteProxyTest`, `ModerationTest`, `ChannelTest`
  (all `:network`, Kotlin); `crates/runtime/tests/network.rs` (the recorded sessions line for line, sealing, a dev
  server's network, a test's other servers, presence and sends, holds and draining, mistakes, no network, the
  requirement, maps and reloads, a hub over TCP sealed, refusals and a config that can't join, moderation, waiting
  for the hub and its policy); and the script test runner's for `nf.test.network_*`. Velocity, S3 and PostgreSQL
  themselves aren't run in tests.

## Plugin interop (Vault and PlaceholderAPI)

`nf.economy` (`balance`, `deposit`, `withdraw`: Vault, `requires:
"plugin:vault"`) and `nf.placeholders` (`parse`, `register`: PlaceholderAPI,
`requires: "plugin:placeholderapi"`). There is no general "call another
plugin": each plugin gets typed functions, and the runtime owns the types.

- **Platform** (`:plugin:platform`'s `Interop.kt`, the spec's source; `netherforge_platform` in Rust): `plugins:
PluginOps` (`is_enabled(name)`, lowercase names as a package declares them), `economy(): EconomyOps?` (none until
  Vault is enabled **and** an economy has registered with it: looked up at every call, never kept) and
  `placeholders: PlaceholderOps` (`parse`, `register(namespace, resolver)`, `is_registered`, `unregister`). Vault's
  and PlaceholderAPI's classes appear only in paper-common's `PaperInterop.kt` (compile-only dependencies in
  `libs.versions.toml`; each adapter's `paperCommon` source set gets them too). Both are soft dependencies in
  `paper-plugin.yml` (`load: BEFORE`, `required: false`, so they enable first when installed and a script's body can
  register placeholders); nothing in the adapter names one's classes unless `PaperPlugins.isEnabled` said so, so the
  JVM never loads a class of a missing plugin (`PaperPlatform.placeholders` is `lazy` for that reason).
- **Presence is never decided once.** `interop.rs`' `Plugins` (a session service) keeps the declared plugins and
  works out which are missing: as the session starts, then again on the first tick and on any tick after
  `PlatformEvents::plugins_changed` (the adapter calls it for `PluginEnableEvent`, `PluginDisableEvent`,
  `ServiceRegisterEvent`, `ServiceUnregisterEvent`; the state in the middle of an event isn't final, so the service
  only sets a flag and looks at the next tick). A missing plugin is the problem `runtime.plugin-missing` (error) on
  each declaring package's manifest, at `$.requires.plugins`, cleared when it enables. A call checks again and fails
  with `nf.economy.balance needs the plugin "vault", which isn't enabled on this server`, or, for Vault without an
  economy, says no economy plugin has registered. The generated binding's requirement check (declared?) runs first.
- **Economy**: `deposit`/`withdraw` return the new balance, `nil` when the
  economy refused (no account, not enough); an amount that isn't positive and
  finite is an error. Vault runs on the main thread, like every script.
- **Placeholders** (`interop.rs`' `Placeholders`, a service): one PlaceholderAPI expansion per namespace
  (`[a-z][a-z0-9]{0,31}`: PlaceholderAPI ends it at the first `_`), owned by the registering script's scope: its
  release and the session's stop unregister it. **Threads**: PlaceholderAPI asks from any thread (async chat,
  scoreboard plugins) and Luau is the main thread's alone. The Platform takes the resolver as an `Rc`, so the runtime
  only ever answers on the main thread; the adapter's glue answers other threads from what the resolver last gave
  per player and key (none before there is one). A callback asked for its own placeholder from inside itself
  (through `parse`) is answered none. After a plugin change, expansions PlaceholderAPI forgot are registered again.
- **Tests**: `InteropTest` against `FakePlatform` (`plugins.enable/disable`,
  `vault.register()`, `placeholders.request(...)` which a test can call from
  another thread); each fake change calls `pluginsChanged()` like the adapter.
  The adapter itself (the Vault and PlaceholderAPI calls) has no integration
  scenario: it needs the real plugins' jars on the test server.

## Threat model (the server's half)

The editor's half (what may run on the user's machine at all, and workspace
trust) is in the tauri-backend skill's "Workspace trust and the threat
model".

**Trusted:** the server owner (`config.yml`, the values in `settings/`,
`/nf` with `netherforge.admin`), Paper, the plugin and its adapter, the
editor that started a dev server (its bridge token); it is held to the
same frame caps as the plugin holds anything on the bridge (see the hot-reload
skill's "Frame size").

**Not trusted:** every package's scripts, the project's own included (the
owner may run a project someone else wrote, and its packages are other
people's code), and players (chat, commands, clicks, item stacks a client
sends).

**Boundaries, and what each enforces:**

- **The Luau sandbox** (plugin-sandbox skill, "The sandbox"): no `io`, `os`,
  `debug`, `package`, `load` of anything but the project's text; budget, time and memory limits; the library's caps.
  Scripts reach the server only through the API, as handles (keys, never Rust or Java objects).
- **Packages** (the session's package names, `Modules::check_exported`): a script names
  only its own package's resources and what its dependencies export;
  `require` across packages only to exports; `nf.data` and settings per
  namespace.
- **Declared capabilities** (`Grants`, above): what a package must ask
  for, held to the package whose code runs; the owner sees the sum at load
  and in `/nf requires`. New powers over the server, the network, storage or
  other plugins go behind a requirement, checked here.
- **Files**: `nf.files` is one sandboxed folder per server
  (`files.rs`' `ScriptFiles`); a production server runs a bundle whose packages are
  hashed (`runtime.bundle-hash`) and never resolves dependencies itself. The
  owner's own packages (`packages.provided`, `project_files.rs`) replace any copy
  a project brings, and a bundle carrying one is refused
  (`runtime.provided-package`), so the code given an owner's allowance is the
  owner's.
- **Inbound web requests** (`nf.web`, see "Web routes" above): off unless the owner turns them
  on; each package only under `/<namespace>/`; refused on the listener's threads before any script
  (size, per-address rate, how many wait), at most 32 routed a tick, handlers under the budget;
  login state and verifiers kept by the runtime, single use, expiring; signatures compared in
  constant time. TLS and slow clients are left to the owner's reverse proxy.
- **The network** (`nf.http.request`, `stream` and `websocket`, see "HTTP" and "Streams and
  WebSockets" above): hosts a package declared; every address checked once and connected to as checked, so a
  name a script's author controls can't lead into the owner's network (public
  addresses only, redirects included, unless the owner sets
  `http.allow-private-addresses`, or gives one package one host and port with
  `http.allow-private`); size, time and rate limits from the owner's
  `http:` section, and for open connections how many, how many bytes a minute on the wire, how
  large a message and how long idle (`http.connections`); the owner's proxy settings never used.
- **WebAssembly** (`nf.wasm`, see "WebAssembly" above): behind `requires.wasm`; a module imports WASI preview 1
  and nothing else (no host function reaches the server), its only files are its own data, read-only, through
  `wasm/files.rs` (never a host path); off the main thread on bounded threads, its memory capped
  per instance and in all, each call held to a time limit, counts per package; traps and timeouts close the
  instance.
- **Networks** (`nf.network`, see "Networks" above): behind `requires.network`; a server joins only with the hub's
  secret or a credential's (proved per connection, never sent) and a name the proxy has (or one its credential may
  register); a package reaches only its own channels and maps and its dependencies'; values are size-checked by the
  server and the hub, each package's map storage capped, sends rate-limited per package; a server that stops reading
  is dropped. A server with the shared secret or a `trusted` credential is trusted with every channel and map; one with
  a `standard` or `isolated` credential (another owner's, or code the network doesn't vouch for) is held by the hub
  to its grants on every frame, so it can't read, write, answer or send outside them, whatever its scripts do. The
  admin API's clients are scoped and never servers; moderation is a grant of its own. Traffic is sealed (`Channel`: AES-256-GCM, a key per
  direction from HKDF over the secret and both nonces, a counter nonce per line), so it can't be read, altered or
  replayed, and a server only accepts a hub that can seal its welcome; strangers before their hello are capped
  (`HubServer.MAX_PENDING`, per address too) and timed out.
- **Remote databases** are named only by the owner (`config.yml`'s
  `databases:`, each listing the packages it's for; plugin-persistence).
- **Plugin interop** (W2.4) has no general escape hatch: only the typed
  `nf.economy` and `nf.placeholders`, each behind its `plugin:` requirement.

## Server-owner settings

`crates/runtime/src/settings.rs` holds each package's declared settings and the owner's values from `<plugin
folder>/settings/<namespace>.json` (format's `read_values`/`write_values`). A change (`Session::set_setting`,
`set_setting_text`, `reload_settings`: the bridge's `set_setting`, `/nf settings set|reset`, the dialog `/nf
settings` opens for a player, or `/nf settings reload` after editing the file) is written to the file at once, then
delivered: `setting_changed` to the package's scripts that listen (only that package's); every other scope of it that
read a changed setting through `nf.config` (reads are remembered per scope) is restarted by reloading its resource; a
full reload reads the files again. Values that don't fit are a problem, never used, never written over; an unreadable
file is left alone. Every change also fires `settings_changed` on the bridge. `crates/runtime/tests/settings.rs` is
the model; `SettingsScenario` runs it on real servers.
