---
name: plugin-capabilities
description: What a package must declare to use (requires) and everything behind those declarations - the one Requirements check, nf.http.request with its address guard and OkHttp, streams and WebSockets, inbound web routes and OAuth logins, WebAssembly (nf.wasm: Chicory, instances, programs, limits, the read-only WASI file system, test stand-ins), server networks (nf.network: the hub on the proxy, the link, channels, requests, shared maps, presence, sending players, the local network of dev servers and tests), Vault and PlaceholderAPI, server-owner settings, and the server's half of the threat model. Read before touching apps/plugin/runtime/.../session/Requirements, .../http, .../web, .../wasm, .../network, packages/network, apps/proxy, .../interop, .../settings, or adding any new power scripts can ask for.
---

# Plugin capabilities

Powers a package has to ask for in its `netherforge.json`, and how each is held to what was asked. Anything new that reaches the network, storage, the server or another plugin goes behind a requirement checked here.

## Declared capabilities (`requires`)

A package declares what its scripts need that a package must ask for in its
`netherforge.json`'s `requires` (format's `ProjectRequires` and
`Requirement`: `moderation`, `db`, `http` hosts, `plugins`), and
`session/Requirements` (a `RuntimeService`, `session.requirements`) holds every
call to it.

- **One check: `session.requirements.check(requirement, what)`.** It takes a
  format `Requirement` (`Requirement.Http("discord.com")`, `Requirement.Db`,
  `Requirement.Plugin("vault")`) or the spec's spelling (`"plugin:vault"`),
  and `what` as the call is named in errors (`nf.http.request`,
  `Player:ban`). It holds the package whose code is running
  (`names.calling()`), never the project that depends on it, and refuses
  with a `LuaApiException` naming the call, the requirement, the package and
  what to add (`Player:ban needs moderation, which package "chat" hasn't
declared: add "requires": { "moderation": true } to its netherforge.json`).
  A tail call that hid the caller is held to every package (see "Packages in
  a session").
- **Who calls it.** The generated primitive of every function whose spec has
  `requires` calls `marshal.requires(...)` (→ `RuntimeApi.requires` → here)
  before reading its arguments, so a spec flag is all most functions need.
  A function that learns more once it has its arguments checks the narrower
  requirement itself: `nf.http.request` (W2.1) checks
  `Requirement.Http(host)` for its URL's host and each redirect's (the spec's
  `requires: "http"` only says some host is declared; a redirect is followed on
  a worker, so its check is `requirements.held(what)`, bound on the main thread
  to the calling package); `nf.db` (W2.2) needs `requires: "db"` (the generated
  check), and a named connection also the owner's `packages` list (W2.3,
  `RemoteDatabases.problem`); a plugin's functions (W2.4) `requires:
"plugin:vault"`. Whether the plugin is installed is the adapter's business
  (a load problem, W2.4), not this check's.
- **Whoever runs the server sees the sum.** `define` takes
  `Requirement.combined(snapshot)` (the tree's requirements, each with the
  namespaces that declare it), `start` logs it in one line ("Requires (by its
  packages' netherforge.json): moderation (test); http:discord.com (test,
  chat)"), and `/nf requires` lists it. A manifest change restarts the
  session, so it's read once per session. `RequirementsTest` is the model.

## HTTP (`nf.http.request`)

`api/NfHttpImpl` checks the call on the main thread (URL, method, headers,
body size, the host requirement, the per-package rate: `http/RequestRate`) and
hands `http/HttpFetcher` the work on a `Workers` thread. Mistakes in the call
are errors at the script's line; the network, the owner's limits and a
redirect's refusal are `nil, err` (`WorkFailed`).

- **OkHttp is the client; the guard is its `Dns`.** `HttpFetcher` holds one
  `OkHttpClient` per session (`http/HttpClient`, a `RuntimeService` that makes
  it on the first request and closes it in `stop`: `dispatcher.cancelAll()`,
  the dispatcher's executor `shutdown()`, `connectionPool.evictAll()`, so a
  reload leaves no threads or sockets). Its `Dns` (`CheckedDns`) resolves a
  name once through the injectable `resolve`, judges every address with
  `AddressGuard` (a name leading anywhere non-public is refused whole), and
  returns exactly those addresses: OkHttp connects to them and never looks the
  name up again. OkHttp doesn't call `Dns` for what it takes to be an IP in the
  URL (its own `[\d.]+` / colon test), so `checkLiteral` judges those first,
  for the URL and each redirect; spellings the JDK would read loosely
  (`2130706433`, `127.1`, `0177.0.0.1`) are refused unread, never guessed at.
  The client is built with `Proxy.NO_PROXY` (proxy environment and properties
  never read), `followRedirects(false)` / `followSslRedirects(false)`,
  `retryOnConnectionFailure(false)` (a POST is never sent twice), no cache, no
  cookie jar, and one `callTimeout` per hop equal to what's left of the
  request's single deadline. Keep-alive is on in a small pool: a pooled
  connection is one that was checked when it was made, so a later request to
  the same host skips the lookup (a redirect to another name always has one).
  TLS is OkHttp's: platform trust, hostname verification, HTTP/2 by ALPN, and
  gzip handled by OkHttp, which is why the size limit counts the decoded body.
  Don't write HTTP by hand again, and don't hand OkHttp a name to resolve
  itself: any other `Dns` or an `InetAddress` it can look up is a hole.
- **The body is read through `source()` in a bounded loop**, failing past
  `http.max-response-bytes` however its length is told (`Content-Length`,
  chunked, a gzip bomb). Headers a script sets must be printable ASCII
  (OkHttp's rule; `NfHttpImpl` says so at the call).
- **Dependencies ship like cron-utils**: `implementation(libs.okhttp)` in the
  runtime, so the adapters' shadow jar carries okhttp, okio and the Kotlin
  stdlib it needs (one stdlib, the build's; nothing is relocated anywhere in
  the build).
- **Redirects are followed by the fetcher, one OkHttp call per hop** (5 at most, one timeout for all):
  each hop gets the host requirement, the address check and a new lookup;
  https is never downgraded to http; `Authorization`, `Cookie` and
  `Proxy-Authorization` stay with their host; 301/302 turn a POST into a GET,
  303 always, 307/308 repeat it.
- **`AddressGuard`** classifies bytes: loopback, private, link-local, CGNAT
  `100.64/10`, benchmarking, reserved and documentation ranges, `fc00::/7`,
  `fe80::/10`, site-local, 6to4/NAT64/Teredo and IPv4-mapped IPv6 (Java hands a
  mapped address over as the `Inet4Address` it holds, so it's judged as that).
  Multicast, broadcast and `0.0.0.0`/`::` are never reachable. The owner's
  `http.allow-private-addresses` (`HttpConfig`, `config.yml`) allows the rest.
- Tests: `AddressGuardTest` (the ranges), `HttpFetcherTest` (OkHttp's
  `mockwebserver3` on loopback; the injectable `resolve` and a `SocketFactory`
  that records the address each connection was asked for and connects to the
  mock server instead, so "public" addresses work offline; redirects, limits,
  timeouts, closing), `HttpRequestTest` (from Lua, with `TestServer(http =
...)`). HTTPS against a real certificate isn't tested.

## Streams and WebSockets (`nf.http.stream`, `nf.http.websocket`)

`api/NfHttpImpl` checks the call as for `request` (URL scheme, credentials, headers, body, the
host requirement; `Sec-WebSocket-*` headers and bad subprotocol names refused) and hands it to
`http/Connections` (a `RuntimeService`, `session.connections`, registered after `http` so it stops
first), which numbers it, files it under the opening scope and makes the `HttpStream`/`WebSocket`
handle (key: number and URL, so `url()` answers after the end).

- **The transport only queues.** `ConnectionTransport` is `http/OkHttpConnections` on a server and
  `http/TestConnections` in a script test run (`HttpClient(testing)`). Either talks to the main
  thread only through the `Connection.Sink`: `opened`, `message`, `ended` (heard once), all
  queued on the connection's `inbound`. `Connections.tick(ASYNC)` hands them to scripts in order,
  at most `messages-per-tick` messages per connection, then checks idle (`idle-seconds`, by ticks)
  and the first answer (`timeout-seconds`). Nothing the transport does runs Lua.
- **Backpressure, not dropping.** `Sink.message` takes a permit of the connection's `room`
  (`max-queued-messages`) and waits for one; the main thread releases one per message delivered.
  A stream's reader or OkHttp's WebSocket reader thread waiting there stops reading the socket,
  so TCP slows the far end. The test transport's `tryMessage` doesn't wait: a full queue is an
  error at the test's line. (This replaced "close on overflow": OkHttp calls `onMessage` on its
  reader thread, so blocking there is real backpressure for WebSockets too.)
- **Limits end, they don't error.** Too many open (`max-per-package`, `max-total`) or the request
  rate (shared with `request`: opening counts) makes the connection anyway and queues
  `ended(LIMIT)` for the next tick, so a script handles every limit in `end`. A script's own
  mistake (a message over `max-message-bytes` sent, a bad close code) is an error at its line.
- **Bytes are counted on the wire.** `bytes-per-minute` is a per-package `ByteRate` (thread-safe
  sliding window). Received bytes are counted under TLS by `CountingSocketFactory`'s sockets
  (each connection's client has its own socket factory and a pool that keeps nothing, so a
  socket is one connection's), which throw `OverByteLimit` once over, after the sink ended it
  `"limit"`: OkHttp has no WebSocket message limit, and this is what stops a far end making the
  server buffer an endless frame. Sent bytes are counted in `Connections.send`.
- **Threads.** A connection holds a thread while it's open, so `OkHttpConnections` has its own
  cached pool (also its OkHttp `Dispatcher`'s), not the runtime's `Workers` (four held streams
  would starve every script's work), bounded by `max-total`. Its `close` cancels every WebSocket
  (an upgraded socket's call is finished, so `Dispatcher.cancelAll` misses it), the dispatcher's
  calls, and the threads. The `HttpFetcher` guard is reused whole: `connectionClient` is
  `client.newBuilder()` (the checked `Dns`, no proxy, no redirects), `checkLiteral` for the URL,
  and a stream follows redirects hop by hop through `redirected` with the bound host check, as
  `fetch` does. WebSockets don't follow redirects (a 3xx is `"failed"`, saying so).
- **Splitting.** `StreamSplitter` turns bytes into lines (LF, CRLF or CR), Server-Sent Events
  (`data:` joined, `event:` → `type`, `id:` kept across events, comments and `retry:` skipped,
  a half event at the end dropped) or decoded chunks (a UTF-8 character split across reads held
  for the next). The same splitter reads the test transport's `receive`.
- **Ending** (`Connections.end`): mark ended, let the far end go (`Link.close`: a WebSocket close
  frame with the script's code, `1001` for `"unloaded"`, `1008` for `"limit"`; a stream's call
  cancelled), drop what's queued, emit `end` while the handle still answers, then remove it and
  `dropTarget`. A script's `close()` hears its own `end` inside the call, as `Effect:stop` does.
- Tests: `StreamSplitterTest`; `HttpConnectionsTest` (from Lua over OkHttp to MockWebServer on
  loopback: a chunked `MockResponseBody` the test feeds, `webSocketUpgrade`; the far end's
  listener must answer a close (`Polite`) or MockWebServer's shutdown gives up waiting); the
  script test runner's `ScriptTestRunnerTest` for `nf.test.connections`/`accept`/`receive`/
  `sent`/`disconnect`.

## Web routes (`nf.web`)

Inbound requests, off by default (`web.enabled`; `WebConfig`). Two halves:

- **The listener's, for the plugin's life: `web/WebGate`** (`runtime.web`). It's served on the
  resource pack's HTTP server (`PackServer`, now four threads; `Packs.listener()` starts it even
  when packs aren't served): `/<sha1>.zip` is the pack's, anything else goes to `PackServer.web`,
  set at `enable` when web routes are on. On the listener's thread the gate refuses what it can
  without the main thread (path over 2048: `414`; `requests-per-minute-per-ip`: `429`,
  `AddressRate`, swept now and then so it can't grow with every address; `Content-Length` or the
  read body over `max-body-bytes`: `413`, reading no further than the limit), then queues a
  `WebCall` in a bounded queue (`max-pending`, else `503`). A `WebCall` is answered once
  (`AtomicBoolean`) and its answer written back on the listener's threads (`PackServer.execute`),
  never the main thread, so a slow client holds up nobody. The client's address is the
  connection's, or `X-Forwarded-For`'s first with `trust-proxy-headers`. No TLS and no slow-client
  timeout of its own: the docs put a reverse proxy in front.
- **The session's: `web/WebRoutes`** (`session.web`). `tick(ASYNC)` routes at most 32 queued
  calls a tick: by the first segment (the namespace), then the most specific matching
  `RoutePath` (literal > `:param` > `*rest`) of that method (`HEAD` → `GET`); `404`, or `405` with
  `Allow`. A script's route is called with `callKept(..., "web")`: a plain `Map` for the request
  (`WebRequest` isn't a crossing shape, only a callback's parameter) and a `WebResponse` handle,
  keyed by the pending request's number. A failed handler is reported (`handlerFailed`) and
  answered `500`; `Absent` is `503`. Pending requests time out to `504` by ticks. Routes are
  scope-owned; `scopeReleased`, `Route:remove` and `stop` answer what waits on them `503`. A route
  is a script's (a `LuaFunction`) or the runtime's (`internal`, a login flow's callback): one
  table, so a script's route and a flow's can't take the same method and path. With web routes off
  routes are still kept (tests reach them), and every package declaring `requires.web` has the
  warning `runtime.web-disabled`.
- **Answers** (`api/NfWebImpl`'s `WebResponseImpl`): status 100–599; headers held to tokens and
  printable ASCII, none the server sets; `max-response-bytes`; every answer gets
  `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` unless set; `redirect` only to
  `http(s)://` or a path starting with one `/`, no control characters.
- **Logins: `web/OAuthFlows`** (`session.oauth`, after `web`). Each flow has an internal route
  `GET /<ns>/oauth/<name>/callback`. `begin` makes a 32-byte state and verifier (`SecureRandom`),
  keeps them by state with the player and an expiry (10 minutes, at most 1000 a flow), and gives
  the authorize URL with `code_challenge` (S256). The callback takes the state out (single use):
  unknown is `400` with no event (forged or replayed); otherwise the code goes to the token
  endpoint on a worker through `HttpFetcher` (the flow's `requires.http` host checked when it's
  made, each redirect held with `requirements.held` bound then), then `complete`/`fail` on the flow
  and an escaped HTML page. In a test run the endpoint isn't called: `nf.test.oauth_token` (or a
  default token) answers, synchronously.
- `nf.web.verify_signature`: HMAC (`javax.crypto.Mac`), compared with `MessageDigest.isEqual`;
  an unreadable signature is `false`.
- **Tests**: `RoutePathTest`; `WebRoutesTest` (real HTTP to the listener with the JDK's client,
  ticking while it waits); the runner's `ScriptTestRunnerTest` for `nf.test.request` (routes
  through `WebRoutes.request`, the gate's refusals included, then advancing tick by tick while the
  answer is pending) and a whole login. The real token exchange isn't tested (it needs HTTPS with
  a trusted certificate), as HTTPS isn't for `nf.http.request`. The conformance harness gets a
  `WebResponse` from a `/sample` route the test requests once before the calls.

## WebAssembly (`nf.wasm`)

`wasm/WasmModules` (`session.wasm`, a `RuntimeService` reloading `WasmModuleKind`) runs the project's modules on
[Chicory](https://chicory.dev) (pure JVM: `runtime`, `wasi`, `compiler`, shaded like okhttp; the compiler's ASM is
the server's own where it bundles one, which Paper 1.21.11 and 26.x do at the same 9.9.1).

- **Compiling** (`CompiledModule.compile`, on `WasmThreads.calls`) as the session defines the project and again
  when a module reloads: parsed with validation, its imports held to WASI preview 1's functions (format's
  `WasmImports`, so `check` and the server agree), SIMD refused (neither Chicory's compiler nor its interpreter runs
  it; the opcode names give it away), then `MachineFactoryCompiler` (interpreter fallback for a function too big
  for one JVM method; a `LinkageError` from an old ASM leaves the module to the interpreter with a warning).
  `wasm.compile: false` skips the compiler. A failure is `runtime.wasm` at `module.wasm` (`problems()`; the
  completion calls `session.problemsChanged()`), and running it fails saying why. One compile per load, shared by
  every instance.
- **Instances** (`CompiledModule.instantiate`): Chicory's WASI (stdin, stdout, stderr, arguments with `argv[0]` the
  module's name, environment, the system clock, `SecureRandom`; **no directories**), with `WasmFiles` put in place of
  every WASI function that takes a descriptor or a path: fd 3 is the module's data at `/` (`WasmData`, its files'
  disk paths worked out once), read by positional `FileChannel` reads as the module reads, never loaded whole;
  writing, making and removing are `EROFS`, a path climbing out is `ENOTCAPABLE`, and no host path ever reaches
  Chicory (whose WASI checks neither rights nor `..`). Memory is `ByteArrayMemory` (half again faster than the
  default `ByteBufferMemory` on Stockfish) capped at `max-memory-mb` through `withMemoryFactory`; `withStart(false)`,
  because Chicory otherwise runs a command's `_start` as the instance is built: the start section runs, `_start` is
  ours to call inside a time limit. A reactor's `_initialize` runs once.
- **Calls** (`WasmCalls`): arguments read from the script's `args` table on the main thread like `Database`'s
  params (integers stay `Long`, so an `i64` is exact) and checked against the export's type there (a mistake is an
  error at the line); strings copied in with the module's `alloc`/`malloc` and freed with `dealloc`/`free` once the
  call returns (never after a trap or a timeout: the instance closes anyway, and running its code again could only
  fail or hide why it failed); `call_text` reads `(ptr, len)` or an `i64` packing both, freed once read. Each
  instance's calls run one at a time on its own `SerialExecutor` (`async/`, the workers' lanes' too) over
  `WasmThreads.calls`; a trap or a timeout closes it (`WasmInstanceRecord.Live.close`: interrupt the running call,
  then on the lane let go of its WASI state and memory reservation; the record stays, so `module()` answers, until
  its scope goes).
- **Time limits** (`WasmThreads.limited`): a watchdog interrupts the calling thread past `max-call-seconds`;
  Chicory's compiled code and interpreter check for it and throw `ChicoryInterruptedException`; the interrupt is
  always cleared after, so a pool thread is never left interrupted. A host function's exception comes out of
  compiled code wrapped, so a WASI exit is found in the cause chain (`exitCode`).
- **Programs** (`WasmProgram`, `nf.wasm.start`): a thread of its own from `WasmThreads.programs` (a program blocks
  reading its input; the pools are bounded by `max-processes-total`, held to before starting). Input is a queue of
  lines (`ProgramInput`, one of the sealed `ProgramInputs`; `finish()` is the end of the file); output is cut into
  lines (`LineOutput`, pieces of `max-line-bytes`, at least 4, cut between UTF-8 characters by `Utf8.wholeCharacters`
  so no piece holds half of one) and queued, each taking a permit of `room` (`max-queued-lines`) and waiting for one:
  **backpressure, not dropping**, as for WebSockets. `tick(ASYNC)` hands out at most `lines-per-tick` per program,
  releasing a permit each, and ends one from its queued `Ended`. Ending from the main thread (stop, unload, limit)
  marks it, finishes its input, interrupts its thread, frees its room, drops what's queued, emits `end` while the
  handle answers, then removes it and `dropTarget`s.
- **Limits** (`WasmConfig`): memory through one `WasmMemoryBudget` (each instance or program reserves
  `max-memory-mb` while it lives, so together they can't pass `max-memory-total-mb` whatever each grows to: Chicory's
  `MemAllocStrategy` can't refuse a growth, so the cap is per instance and the budget an upper bound); instances
  per package (held and in flight, main thread); programs per package and in all (a refused one ends `"limit"` at
  the next tick, as connections do); queued calls per package (`submit`, from any thread). Threads run at
  `MIN_PRIORITY`.
- **Scope and reload**: everything is filed under its scope and ends in `scopeReleased` (instances closed,
  programs `"unloaded"`, stand-ins released). `resolve` records each scope that names a module; reloading a module
  `batch.add`s those scopes' resources (their kinds come after `WasmModuleKind` in `Kinds.all`), so they restart on
  the new one. Don't call `batch.first` there: it marks the resource reloaded and the restart never happens.
- **Test runs**: modules really run. `settle(millis)` is what a test's tick waits on (`NetherForgeRuntime.settled`,
  beside the workers' `idle`): no call or compile pending, and every program `quiet` (done, waiting for room, or
  reading with nothing queued; a line just sent it hasn't woken for doesn't count), a busy one at most a second a
  tick. `nf.test.stub_wasm` stands a test's functions in for a module: `WasmStandIns` keeps them and answers in the
  module's place (`WasmStubCalls`, on `session.async.mainThread`), so nothing of a stand-in is on Chicory's path.
  `WasmModules` asks `standingIn(key)` first and hands the rest over: an instance is a `WasmInstanceRecord.StandIn`
  (the sealed record's other case is `Live`), counted against the same limits; a started program's input is a
  `StandInInput` (no thread runs it), whose lines the stand-in's `input` answers in `tick`, and its end once the
  input closes.
- Tests: `WasmModulesTest` (real modules from `WasmFixtures`, WAT built by wabt's wat2wasm in the test fixtures: a
  library, an echo program, a data reader, an invalid module, SIMD, a free that fails after a trap),
  `WasmProgramTest` (lines and UTF-8 cuts), `ScriptTestRunnerTest` for stand-ins and their limits, the
  conformance harness (an instance made by a module started before the harness, so the ticks it takes leave the
  harness's connections fresh), and `WasmBenchmark` (by hand: a loop compiled and interpreted, and Stockfish's
  `bench` from `NETHERFORGE_STOCKFISH`).

## Networks (`nf.network`)

Servers behind one Velocity proxy, talking through a **hub** the proxy runs. Three places:

- **`packages/network` (`:network`)**: the protocol and the hub, with nothing of a proxy or a server in it, so the
  Paper plugin, the proxy plugin and tests share one. `Protocol` (NDJSON, one JSON object a line, its kind in `"t"`:
  `HubFrame`s the hub sends, `ServerFrame`s a server sends; plain frames, not JSON-RPC, since almost everything is a
  notification and the hub relays requests under ids of its own). The handshake: the hub's `Challenge` (a nonce), the
  server's `Hello` with its name and `Auth.proof` (HMAC-SHA256 of nonce and name under the shared secret: the secret
  never crosses, and a proof is good for one connection and one name), then `Welcome` (the network as it is, and the
  hub's `maxValueBytes`) or `Refused`. `Hub` is one lock over everything (sessions by name, presence, `SharedMaps`,
  request routes): frames arrive on each connection's thread, the proxy's events on its, `tick` on a timer, and
  sending only queues (`Peer.send`), so nothing waits on a socket or the proxy while holding it; the proxy is asked
  to move a player outside the lock (`handle` returns what runs after). `HubServer` is it on TCP (a reader and a
  writer thread a connection, a bounded queue that drops a server that stops reading, a read timeout servers' pings
  keep alive); `HubClient` is a server's end (connects, answers the challenge, reconnects with backoff, pings when
  idle, throws away what was queued when the connection drops); `LocalLink` is a server in the same JVM, everything
  on the calling thread. `SharedMaps` keeps values with expiries and holds each package (a map's namespace) to
  `maxKeysPerPackage`/`maxBytesPerPackage`; `FileMapStore` saves them whole (temp file, atomic move).
- **`apps/proxy/velocity` (`:proxy:velocity`, Java 25 like Velocity 4)**: `NetherForgeProxy` runs the hub with
  `HubSettings` (`hub.properties`, written with `Auth.newSecret()` on first start: a hub is never open without one),
  tells it of `ServerConnectedEvent` and `DisconnectEvent` (`PostOrder.LAST`), moves players with
  `createConnectionRequest(server).connect()` (each failed status said in words), and adds `/nfhub`. The descriptor
  is a hand-written `velocity-plugin.json` (no annotation processing in a Kotlin build). A server's name must be one
  the proxy has (matched ignoring case, as Velocity does).
- **The editor's dev networks**: a dev server started with `-Dnetherforge.network.hub` (and `.server`, and the secret
  in `NETHERFORGE_NETWORK_SECRET`, never on the command line) is in that network whatever `config.yml` says; the
  owner's limits stay (`RuntimeConfig.devNetwork`). The editor runs the proxy and the servers (tauri-backend's "Dev
  networks"). A remote link's reader interrupted while it queues (the link stopping) drops what it was saying.
- **The runtime's `network/`**: `NetworkLink` (`runtime.network`, the plugin's life: a reload doesn't reconnect) is
  the link and what the hub said (presence, servers, arrivals), applied on the main thread as it's drained. Its
  `mode`: `REMOTE` (`network.enabled`, a `HubClient`; its reader waits for room past `max-queued-messages`, which
  stops it reading the socket: backpressure, not dropping), `LOCAL` (a dev server not in a network, named `dev`, and a
  script test run, `test`: a `Hub` in the JVM behind a `LocalProxy` whose players are this server's own, joined and
  quit through the session's hooks; a test's other servers are `TestPeer`s on the same hub), or `OFF` (a production
  server not in one: the warning `runtime.network-disabled` on each declaring package). A local link connects as it
  starts, so its state is there before the first tick. Ids for requests, writes and sends are one sequence for the
  plugin's life, so an old session's answer never completes a new one's wait. `Network` (`session.network`) drains
  in `ASYNC` (at most `messages-per-tick`), raises the events, answers requests (`event.reply`, read back as JSON by
  `LuaCodecs.DATA`), keeps each map's copy (`Mirror`: a snapshot when it starts listening, then every change), times
  out what waits, delivers arrivals the tick after the player joined, and tells the hub what it listens to whenever a
  handler count changes (`Scripts.counting`) or a map is first got. A lost connection fails every wait.
- **Names**: a channel's or map's full name is `namespace:name` (`Network.resolve`: bare is the caller's package,
  `ns:name` only for its own or a package it depends on), so packages never see each other's otherwise. Handles are
  keyed by the full name and spelled back bare for their own package.
- **Limits**: the server's `network:` section (`NetworkConfig`: per-package `messages-per-minute` through the same
  `RequestRate` as HTTP; past it `publish`/`set` are `false` and waits fail) and the hub's `hub.properties`
  (`NetworkLimits`: value size, which a server checks too, at the script's line, from the welcome's
  `maxValueBytes`; maps per package; how long the hub routes a request; how far behind a server may fall).
- **Tests**: `HubTest` (the hub through local links and once over TCP: handshake and refusals, relaying, requests and
  their failures, maps with expiry, limits and saving, presence and sending), `HubSettingsTest`, `NetworkTest` (a hub
  on loopback and two `TestServer`s with `network = NetworkConfig(...)`: every part from scripts, a lost hub,
  mistakes, the requirement, `/nf network`), and the runner's `ScriptTestRunnerTest` for `nf.test.network_*`.

## Plugin interop (Vault and PlaceholderAPI)

`nf.economy` (`balance`, `deposit`, `withdraw`: Vault, `requires:
"plugin:vault"`) and `nf.placeholders` (`parse`, `register`: PlaceholderAPI,
`requires: "plugin:placeholderapi"`). There is no general "call another
plugin": each plugin gets typed functions, and the runtime owns the types.

- **Platform** (`platform/Interop.kt`): `plugins: PluginOps`
  (`isEnabled(name)`, lowercase names as a package declares them),
  `economy(): EconomyOps?` (null until Vault is enabled **and** an economy has
  registered with it: looked up at every call, never kept) and
  `placeholders: PlaceholderOps` (`parse`, `register(namespace, resolver)`,
  `isRegistered`, `unregister`). Vault's and PlaceholderAPI's classes appear
  only in paper-common's `PaperInterop.kt` (compile-only dependencies in
  `libs.versions.toml`; each adapter's `paperCommon` source set gets them too).
  Both are soft dependencies in `paper-plugin.yml` (`load: BEFORE`,
  `required: false`, so they enable first when installed and a script's body
  can register placeholders); nothing in the adapter names one's classes
  unless `PaperPlugins.isEnabled` said so, so the JVM never loads a class of a
  missing plugin (`PaperPlatform.placeholders` is `lazy` for that reason).
- **Presence is never decided once.** `interop/Plugins` (a service,
  `session.plugins`) keeps the declared plugins (from `Requirement.combined`)
  and works out which are missing: at `start`, then again on the first tick
  and on any tick after `PlatformEvents.pluginsChanged()` (the adapter calls it
  for `PluginEnableEvent`, `PluginDisableEvent`, `ServiceRegisterEvent`,
  `ServiceUnregisterEvent`; the state in the middle of an event isn't final, so
  the service only sets a flag and looks in `TickPhase.UPKEEP`). A missing
  plugin is the problem `runtime.plugin-missing` (error) on each declaring
  package's manifest, at `$.requires.plugins`, cleared when it enables. A call
  checks again (`Plugins.require`) and fails with `nf.economy.balance needs the
plugin "vault", which isn't enabled on this server`, or, for Vault without
  an economy, says no economy plugin has registered. The generated binding's
  `Requirements.check` (declared?) runs first.
- **Economy**: `deposit`/`withdraw` return the new balance, `nil` when the
  economy refused (no account, not enough); an amount that isn't positive and
  finite is an error. Vault runs on the main thread, like every script.
- **Placeholders** (`interop/Placeholders`, a service): one PlaceholderAPI
  expansion per namespace (`[a-z][a-z0-9]{0,31}`: PlaceholderAPI ends it at the
  first `_`), owned by the registering script's scope: `scopeReleased` and
  `stop` unregister it and unref the callback. **Threads**: PlaceholderAPI asks
  from any thread (async chat, scoreboard plugins) and the Lua state is the main
  thread's alone, so the resolver runs the callback only on the main thread
  (remembered from the first `register`, which is always a script's call) and
  keeps its answer per player and key; off the main thread it returns that last
  answer (null before there is one) and touches no Lua. A callback asked for
  its own placeholder from inside itself (through `parse`) is answered null.
  After a plugin change, expansions PlaceholderAPI forgot (it reloaded or
  enabled again) are registered again. A callback's error is reported as any
  handler's (rate limited, file and line) and answers null.
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

- **The Lua sandbox** (plugin-sandbox skill, "The sandbox"): no `io`, `os`, `debug`,
  `package`, `load` of anything but the project's text, raw `java`; budget,
  time and memory limits; the library's caps; no `__gc`. Scripts reach the
  server only through the API, as handles (keys, never Java objects) and
  primitives they can't see.
- **Packages** (`PackageNames`, `Modules.checkExported`): a script names
  only its own package's resources and what its dependencies export;
  `require` across packages only to exports; `nf.data` and settings per
  namespace.
- **Declared capabilities** (`Requirements`, above): what a package must ask
  for, held to the package whose code runs; the owner sees the sum at load
  and in `/nf requires`. New powers over the server, the network, storage or
  other plugins go behind a requirement, checked here.
- **Files**: `nf.files` is one sandboxed folder per server
  (`ScriptFiles`); a production server runs a bundle whose packages are
  hashed (`runtime.bundle-hash`) and never resolves dependencies itself.
- **Inbound web requests** (`nf.web`, see "Web routes" above): off unless the owner turns them
  on; each package only under `/<namespace>/`; refused on the listener's threads before any script
  (size, per-address rate, how many wait), at most 32 routed a tick, handlers under the budget;
  login state and verifiers kept by the runtime, single use, expiring; signatures compared in
  constant time. TLS and slow clients are left to the owner's reverse proxy.
- **The network** (`nf.http.request`, `stream` and `websocket`, see "HTTP" and "Streams and
  WebSockets" above): hosts a package declared; every address checked once and connected to as checked, so a
  name a script's author controls can't lead into the owner's network (public
  addresses only, redirects included, unless the owner sets
  `http.allow-private-addresses`); size, time and rate limits from the owner's
  `http:` section, and for open connections how many, how many bytes a minute on the wire, how
  large a message and how long idle (`http.connections`); the owner's proxy settings never used.
- **WebAssembly** (`nf.wasm`, see "WebAssembly" above): behind `requires.wasm`; a module imports WASI preview 1
  and nothing else (no host function reaches the server), its only files are its own data, read-only, through
  `WasmFiles` (never a host path); off the main thread on bounded threads at the lowest priority, its memory capped
  per instance and in all, each call held to a time limit, counts per package; traps and timeouts close the
  instance.
- **Networks** (`nf.network`, see "Networks" above): behind `requires.network`; a server joins only with the hub's
  secret (proved per connection, never sent) and a name the proxy has; a package reaches only its own channels and
  maps and its dependencies'; values are size-checked by the server and the hub, each package's map storage capped,
  sends rate-limited per package; a server that stops reading is dropped. Every server in the network is trusted with
  every channel and map: the network is one owner's servers. Traffic isn't encrypted: the hub's port belongs on the
  owner's own network.
- **Remote databases** are named only by the owner (`config.yml`'s
  `databases:`, each listing the packages it's for; plugin-persistence).
- **Plugin interop** (W2.4) has no general escape hatch: only the typed
  `nf.economy` and `nf.placeholders`, each behind its `plugin:` requirement.

## Server-owner settings

`settings/OwnerSettings` (a `RuntimeService`, `session.settings`) holds each
package's declared settings and the owner's values from
`<plugin folder>/settings/<namespace>.json` (format's `SettingValues`). A
change (`set`, the bridge's `set_setting`, `/nf settings set|reset`, the dialog
`SettingsDialog` that `/nf settings` opens for a player, or `/nf settings
reload` after editing the file) is written to the file, then delivered:
`Scripts.emitTo` gives `setting_changed` to the package's scripts that listen;
every other scope of it that read a changed setting through `nf.config` (reads
are remembered per scope) is restarted by reloading its resource; a full
reload restarts everything anyway. Values that don't fit are a problem, never
used, never written over; an unreadable file is left alone. Every change also
fires `settings_changed` on the bridge. `SettingsTest` is the model (the
fake's files); `SettingsScenario` runs it on real servers.
