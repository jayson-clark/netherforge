import { asyncFunction } from '../async.ts'
import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

/** `nf.http`: requests to web services. */
export const nfHttp: LuaClass = {
  name: 'nf.http',
  doc: 'Requests to web services (a Discord webhook, a status API). A package declares the hosts it talks to in `netherforge.json`\'s `requires` (`"http": ["discord.com"]`, or `"*.discord.com"` for every name under it), and the server owner sees every host the project and its packages declare when it starts (`/nf requires`); a request to any other host is an error. Every request is held to the package whose code makes it. The server looks a name up once, checks the addresses it leads to and connects to exactly the address it checked: a loopback, private, link-local, shared (`100.64.0.0/10`), unique-local or IPv4-mapped address is refused even when its name is declared, unless the server owner turned on `http.allow-private-addresses` in `config.yml` (for a service on the same machine or network). Redirects (at most 5) are followed, and each one is held to the same rules, host declaration included. The server\'s proxy settings are never used. Only `http` and `https` URLs work. The server owner also limits requests in `config.yml`\'s `http:` section: how large a request and a response may be, how long a request may take, and how many a package may make a minute. Connections that stay open (`stream`, `websocket`) are held to the same hosts and addresses, count as a request when they open, and have limits of their own in `http.connections`.',
  methods: false,
  fields: [],
  functions: [
    asyncFunction({
      name: 'request',
      doc: "Sends an HTTP request and gives the response. Any response is a result, whatever its status: a `404` or `500` is `response.status`, not an error, so check it. `nil, err` is for a request that couldn't be made or finished: a name that doesn't resolve, an address that isn't allowed, a refused connection, a timeout, a response over the size limit, too many requests this minute, a redirect to a host the package didn't declare, or more than 5 redirects. A mistake in the call itself (a URL that isn't `http` or `https`, a host the package didn't declare, a body over the limit, a header that can't be sent) is an error at the script's line.",
      requires: 'http',
      params: [
        {
          name: 'options',
          type: 'HttpRequestOptions',
          doc: 'The request: its `url`, and optionally its `method`, `headers` and `body`.',
        },
      ],
      value: {
        name: 'response',
        type: 'HttpResponse',
        doc: 'the response: its status, headers and body',
      },
      example:
        'nf.task(function()\n  local response, err = nf.http.request({\n    method = "POST",\n    url = "https://discord.com/api/webhooks/123/token",\n    headers = { ["content-type"] = "application/json" },\n    body = nf.json.encode({ content = "someone joined" }),\n  })\n  if not response then\n    log("webhook failed: " .. err)\n  elseif response.status >= 400 then\n    log("webhook said " .. response.status)\n  end\nend)',
    }),
    {
      name: 'stream',
      doc: "Opens a request whose response keeps coming (an NDJSON feed, Server-Sent Events, any chunked stream) and hands it over piece by piece as the stream's `message` events, on the main thread, as the server sends it. It returns at once: the connection is made off the main thread, and its `open` event says when the response has begun (with its status, which may be an error's: check it). It ends once, with its `end` event saying why: the server finished, `close()` was called, it failed, an owner's limit was reached, nothing came for too long, or the script that opened it unloaded. Nothing reconnects by itself: open it again from `end` if you want to, waiting longer each time. Redirects are followed as for `request`. The hosts, addresses and request rules are `request`'s, and opening counts as a request against the package's rate; a mistake in the call is an error at the script's line, and a limit the owner set (too many open, the request rate) ends the stream at the next tick with reason `\"limit\"`.",
      requires: 'http',
      params: [
        {
          name: 'options',
          type: 'HttpStreamOptions',
          doc: 'The request (`url`, and optionally `method`, `headers` and `body`) and how to split what comes back (`format`).',
        },
      ],
      returns: [
        { type: 'HttpStream', doc: 'The stream: listen to its `open`, `message` and `end`.' },
      ],
      example:
        'local function follow()\n  local feed = nf.http.stream({ url = "https://status.example.com/events", format = "events" })\n  feed:on("message", function(event)\n    if event.type == "incident" then\n      nf.server.broadcast("<red>" .. nf.json.decode(event.data).title)\n    end\n  end)\n  feed:on("end", function(event)\n    if event.reason ~= "unloaded" then\n      nf.after(20 * 30, follow)\n    end\n  end)\nend\nfollow()',
    },
    {
      name: 'websocket',
      doc: "Opens a WebSocket (a `ws://` or `wss://` URL): a connection both sides send text messages over, for as long as it stays open (a chat bridge, a game feed, Discord's gateway). It returns at once: the connection is made off the main thread, and the socket's `open` event says when it's ready (what `send` sends before then waits for it). Each text message from the other side is a `message` event on the main thread, in order. It ends once, with its `end` event saying why. Nothing reconnects by itself. The host must be one the package declares in `requires.http`, as for `request` (the same declaration covers both), and the address rules are `request`'s; redirects aren't followed. A mistake in the call is an error at the script's line, and a limit the owner set (too many open, the request rate) ends it at the next tick with reason `\"limit\"`.",
      requires: 'http',
      params: [
        {
          name: 'options',
          type: 'WebSocketOptions',
          doc: 'Where to connect (`url`), and optionally `headers` and the `protocols` to offer.',
        },
      ],
      returns: [
        {
          type: 'WebSocket',
          doc: 'The socket: listen to its `open`, `message` and `end`, and `send` on it.',
        },
      ],
      example:
        'local socket = nf.http.websocket({ url = "wss://chat.example.com/live" })\nsocket:on("open", function()\n  socket:send(nf.json.encode({ hello = "minecraft" }))\nend)\nsocket:on("message", function(event)\n  local said = nf.json.decode(event.data)\n  nf.server.broadcast("<gray>[web] " .. said.author .. ": " .. said.text)\nend)\nnf.on("player_chat", function(event)\n  socket:send(nf.json.encode({ author = event.player:name(), text = event.message }))\nend)',
    },
  ],
}

/** The plain tables `nf.http.request` takes and gives. */
export const httpShapes: LuaClass[] = [
  {
    name: 'HttpRequestOptions',
    doc: 'What `nf.http.request` takes.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'url',
        type: 'string',
        doc: 'An `http://` or `https://` URL, without a user name or password in it (send credentials in a header). Its host must be one the calling package declares in `requires`.',
      },
      {
        name: 'method',
        type: '"GET"|"POST"|"PUT"|"PATCH"|"DELETE"|"HEAD"|"OPTIONS"?',
        doc: 'The HTTP method; `"GET"` when left out.',
      },
      {
        name: 'headers',
        type: 'table<string, string>?',
        doc: 'Request headers by name. `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade` and the like are set by the server and refused here. A `User-Agent` of `NetherForge` is sent unless you give one. `Authorization`, `Cookie` and `Proxy-Authorization` are dropped when a redirect leads to another host.',
      },
      {
        name: 'body',
        type: 'string?',
        doc: "The request body, as text (UTF-8). Its size is limited by `http.max-request-bytes` in the server's `config.yml`. A `GET`, `HEAD` or `OPTIONS` can't have one.",
      },
    ],
  },
  {
    name: 'HttpResponse',
    doc: 'What `nf.http.request` gives.',
    methods: false,
    functions: [],
    fields: [
      { name: 'status', type: 'integer', doc: 'The status code: `200`, `404`.' },
      {
        name: 'headers',
        type: 'table<string, string>',
        doc: 'The response headers by name in lowercase (`response.headers["content-type"]`); a header sent more than once is its values joined with `, `.',
      },
      {
        name: 'body',
        type: 'string',
        doc: "The body as text, decoded by the charset the response names (UTF-8 when it names none), so a response that isn't text (an image) doesn't survive. Its size is limited by `http.max-response-bytes` in the server's `config.yml`.",
      },
      {
        name: 'url',
        type: 'string',
        doc: "The URL the response came from: the request's own, or where its redirects ended.",
      },
    ],
  },
]

const LIMIT_REASON =
  "an owner's limit in `config.yml`'s `http:` section was reached (too many connections open, the request rate, a message over `connections.max-message-bytes`, the package's `connections.bytes-per-minute`)"

const streamEvents: EventSpec[] = [
  {
    name: 'open',
    doc: "The response has begun: its status and headers are in. Any status opens it, so check `event.status`: an error's body arrives as messages like any other. Heard at most once, never after `end`.",
    payload: 'HttpStreamOpenEvent',
  },
  {
    name: 'message',
    doc: "A piece of the response arrived: a line, a Server-Sent Event or a chunk, as the stream's `format` says. Messages come in order, on the main thread, a limited number a tick per stream (`connections.messages-per-tick`); while scripts haven't taken them, at most `connections.max-queued-messages` wait and the server stops reading from the network, so nothing is ever dropped.",
    payload: 'HttpStreamMessageEvent',
  },
  {
    name: 'end',
    doc: 'The stream ended, for any reason (`event.reason` says which): heard once, after every message that arrived before it, while the handle still answers. After it, every handler on the stream is gone. Opening a new stream is how to reconnect.',
    payload: 'HttpStreamEndEvent',
    example:
      'stream:on("end", function(event)\n  if event.reason == "failed" then\n    log("feed failed: " .. event.detail)\n  end\nend)',
  },
]

/** A streamed HTTP response, from `nf.http.stream`. */
export const httpStreamClass: LuaClass = {
  name: 'HttpStream',
  doc: 'A streamed HTTP response, from `nf.http.stream`: its `open`, each `message` and its one `end` are events on it. It belongs to the script that opened it and ends with reason `"unloaded"` when that script unloads (a reload, the server stopping). Once it has ended its methods answer as an ended stream does rather than erroring. Ephemeral: it can\'t be saved in a `data()` table.',
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'url', type: 'string' },
    ],
  },
  fields: [],
  events: streamEvents,
  functions: [
    {
      name: 'url',
      doc: 'The URL it was opened with, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'state',
      doc: '`"connecting"` until its `open` event, then `"open"`, and `"ended"` once its `end` event has been heard.',
      params: [],
      returns: [{ type: '"connecting"|"open"|"ended"' }],
    },
    {
      name: 'status',
      doc: "The response's status code once it has opened (`200`, `404`); `nil` before, and once it has ended (its `end` event has it).",
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'close',
      doc: 'Ends it now, with reason `"closed"`: the connection is dropped and messages that arrived but weren\'t handed over yet are thrown away.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    ...eventFunctions(
      'HttpStream',
      'stream',
      'local stream = nf.http.stream({ url = "https://feed.example.com/live.ndjson" })\nstream:on("message", function(event)\n  local item = nf.json.decode(event.data)\n  log("got", item.kind)\nend)',
    ),
  ],
}

const socketEvents: EventSpec[] = [
  {
    name: 'open',
    doc: 'The connection is made and ready. What `send` sent before it goes now, in order. Heard at most once, never after `end`.',
    payload: 'WebSocketOpenEvent',
  },
  {
    name: 'message',
    doc: 'A text message arrived. Messages come in order, on the main thread, a limited number a tick per socket (`connections.messages-per-tick`); while scripts haven\'t taken them, at most `connections.max-queued-messages` wait and the server stops reading from the network, so nothing is ever dropped. A binary message isn\'t taken: the socket is closed (code `1003`) and ends `"failed"`.',
    payload: 'WebSocketMessageEvent',
  },
  {
    name: 'end',
    doc: 'The socket ended, for any reason (`event.reason` says which): heard once, after every message that arrived before it, while the handle still answers. After it, every handler on the socket is gone. Opening a new socket is how to reconnect.',
    payload: 'WebSocketEndEvent',
    example:
      'socket:on("end", function(event)\n  log("socket ended", event.reason, event.code, event.detail)\nend)',
  },
]

/** An open WebSocket, from `nf.http.websocket`. */
export const webSocketClass: LuaClass = {
  name: 'WebSocket',
  doc: 'A WebSocket, from `nf.http.websocket`: `send` text on it, and hear its `open`, each `message` and its one `end` as events. It belongs to the script that opened it and ends with reason `"unloaded"` when that script unloads (a reload, the server stopping). Once it has ended its methods answer as an ended socket does rather than erroring. Ephemeral: it can\'t be saved in a `data()` table.',
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'url', type: 'string' },
    ],
  },
  fields: [],
  events: socketEvents,
  functions: [
    {
      name: 'url',
      doc: 'The URL it was opened with, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'state',
      doc: '`"connecting"` until its `open` event, then `"open"`, and `"ended"` once its `end` event has been heard.',
      params: [],
      returns: [{ type: '"connecting"|"open"|"ended"' }],
    },
    {
      name: 'protocol',
      doc: "The subprotocol the other side chose from `options.protocols`, while it's open; `nil` before and after, or when it chose none.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'send',
      doc: 'Sends a text message. Before the socket opens it waits and goes, in order, once it does. A message over `connections.max-message-bytes` is an error at the script\'s line; one past the package\'s `connections.bytes-per-minute` ends the socket (reason `"limit"`) instead of going.',
      params: [{ name: 'text', type: 'string', doc: 'The message, as text (UTF-8).' }],
      returns: [
        {
          type: 'boolean',
          doc: '`false` when the socket has ended (or just ended over the limit), so nothing was sent.',
        },
      ],
      example: 'socket:send(nf.json.encode({ op = 1, d = sequence }))',
    },
    {
      name: 'close',
      doc: 'Ends it now, with reason `"closed"`: the other side is told with `code` and `reason`, and messages that arrived but weren\'t handed over yet are thrown away.',
      params: [
        {
          name: 'code',
          type: 'integer',
          doc: 'The close code: `1000` (a normal close, the default) or one from `3000` to `4999`, which applications define.',
          optional: true,
        },
        {
          name: 'reason',
          type: 'string',
          doc: 'Why, for the other side to read: at most 123 bytes.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    ...eventFunctions(
      'WebSocket',
      'socket',
      'local socket = nf.http.websocket({ url = "wss://chat.example.com/live" })\nsocket:on("message", function(event)\n  log("said:", event.data)\nend)',
    ),
  ],
}

const event = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  extends: 'Event',
})

/** The tables `nf.http.stream` and `nf.http.websocket` take, and their events. */
export const connectionShapes: LuaClass[] = [
  {
    name: 'HttpStreamOptions',
    doc: "What `nf.http.stream` takes: `nf.http.request`'s options, and `format`.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'url',
        type: 'string',
        doc: 'An `http://` or `https://` URL, as for `nf.http.request`. Its host must be one the calling package declares in `requires`.',
      },
      {
        name: 'method',
        type: '"GET"|"POST"|"PUT"|"PATCH"|"DELETE"|"OPTIONS"?',
        doc: 'The HTTP method; `"GET"` when left out.',
      },
      {
        name: 'headers',
        type: 'table<string, string>?',
        doc: 'Request headers by name, as for `nf.http.request`. A `format` of `"events"` sends `Accept: text/event-stream` unless you give an `Accept`.',
      },
      {
        name: 'body',
        type: 'string?',
        doc: 'The request body, as for `nf.http.request` (`http.max-request-bytes`).',
      },
      {
        name: 'format',
        type: '"lines"|"events"|"chunks"?',
        doc: 'How the response is split into messages. `"lines"` (the default): one message per line, without its line ending (NDJSON, a log tail). `"events"`: Server-Sent Events (`text/event-stream`), one message per event, with its `type` and `id`; comments and `retry` are skipped. `"chunks"`: the text as it arrives, in pieces of no particular size. A line or event over `connections.max-message-bytes` ends the stream (reason `"limit"`); text is UTF-8.',
      },
    ],
  },
  {
    name: 'WebSocketOptions',
    doc: 'What `nf.http.websocket` takes.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'url',
        type: 'string',
        doc: 'A `ws://` or `wss://` URL, without a user name or password in it. Its host must be one the calling package declares in `requires.http`.',
      },
      {
        name: 'headers',
        type: 'table<string, string>?',
        doc: "Headers for the opening request (an `Authorization`), with `nf.http.request`'s rules. The `Sec-WebSocket-*` headers are the server's to set.",
      },
      {
        name: 'protocols',
        type: 'string[]?',
        doc: 'Subprotocols to offer (`Sec-WebSocket-Protocol`), in order of preference; `socket:protocol()` says which one the other side chose.',
      },
    ],
  },
  event('HttpStreamOpenEvent', "A stream's response began.", [
    { name: 'stream', type: 'HttpStream', doc: 'The stream.' },
    { name: 'status', type: 'integer', doc: 'The status code: `200`, `404`.' },
    {
      name: 'headers',
      type: 'table<string, string>',
      doc: 'The response headers by name in lowercase, as `nf.http.request` gives them.',
    },
  ]),
  event('HttpStreamMessageEvent', 'A piece of a stream arrived.', [
    { name: 'stream', type: 'HttpStream', doc: 'The stream.' },
    {
      name: 'data',
      type: 'string',
      doc: "The line (without its line ending), the event's data (its `data:` lines joined with line breaks), or the chunk.",
    },
    {
      name: 'type',
      type: 'string?',
      doc: 'For `format = "events"`: the event\'s type, `"message"` when it names none. `nil` for the other formats.',
    },
    {
      name: 'id',
      type: 'string?',
      doc: 'For `format = "events"`: the last event id the stream set (`id:`), which a script reconnecting sends back as `Last-Event-ID`. `nil` when none was set, and for the other formats.',
    },
  ]),
  event('HttpStreamEndEvent', 'A stream ended.', [
    { name: 'stream', type: 'HttpStream', doc: 'The stream.' },
    {
      name: 'reason',
      type: '"finished"|"closed"|"failed"|"limit"|"idle"|"unloaded"',
      doc: `Why: the server ended the response; \`close()\` was called; the connection couldn't be made or broke (a name that doesn't resolve, an address that isn't allowed, a refused connection, a redirect it couldn't follow, no answer within \`http.timeout-seconds\`); ${LIMIT_REASON}; nothing arrived for \`connections.idle-seconds\`; or the script that opened it unloaded.`,
    },
    { name: 'status', type: 'integer?', doc: "The response's status, when it had opened." },
    {
      name: 'detail',
      type: 'string?',
      doc: 'What went wrong, in words, for `"failed"` and `"limit"`; `nil` otherwise.',
    },
  ]),
  event('WebSocketOpenEvent', 'A WebSocket opened.', [
    { name: 'socket', type: 'WebSocket', doc: 'The socket.' },
    {
      name: 'protocol',
      type: 'string?',
      doc: 'The subprotocol the other side chose, or `nil`.',
    },
    {
      name: 'headers',
      type: 'table<string, string>',
      doc: "The opening response's headers by name in lowercase.",
    },
  ]),
  event('WebSocketMessageEvent', 'A text message arrived on a WebSocket.', [
    { name: 'socket', type: 'WebSocket', doc: 'The socket.' },
    { name: 'data', type: 'string', doc: 'The message.' },
  ]),
  event('WebSocketEndEvent', 'A WebSocket ended.', [
    { name: 'socket', type: 'WebSocket', doc: 'The socket.' },
    {
      name: 'reason',
      type: '"finished"|"closed"|"failed"|"limit"|"idle"|"unloaded"',
      doc: `Why: the other side closed it (\`code\` and \`detail\` say how); \`close()\` was called; the connection couldn't be made, was refused or broke (as for a stream; a server that doesn't speak WebSocket at that URL, or a binary message); ${LIMIT_REASON}; nothing was sent or arrived for \`connections.idle-seconds\`; or the script that opened it unloaded.`,
    },
    {
      name: 'code',
      type: 'integer?',
      doc: 'For `"finished"`: the close code the other side sent (`1000` is a normal close).',
    },
    {
      name: 'detail',
      type: 'string?',
      doc: 'For `"finished"`: the reason the other side gave, if any. For `"failed"` and `"limit"`: what went wrong, in words. `nil` otherwise.',
    },
  ]),
]
