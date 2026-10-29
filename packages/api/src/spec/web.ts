import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const shape = (
  name: string,
  doc: string,
  fields: LuaClass['fields'],
  extend?: string,
): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  ...(extend ? { extends: extend } : {}),
})

/** `nf.web`: answering web requests to the server. */
export const nfWeb: LuaClass = {
  name: 'nf.web',
  doc: 'Web requests to the server: webhooks from stores, CI and chat services, "log in with…" callbacks, small pages and JSON for a companion site. A package declares `"requires": { "web": true }` in its `netherforge.json`, and its routes live under its own path, `/<namespace>/`, so packages can\'t collide. They\'re served only once the server owner turns web routes on (`web.enabled` in `config.yml`), on the port the resource pack is served from, usually behind a reverse proxy that adds HTTPS; until then a package that declares them has a warning (`runtime.web-disabled`). Every request waits for the main thread: handlers run there, under the script\'s budget, like any handler, and the owner limits how large a request may be, how many each address may make a minute and how many may wait (`web:` in `config.yml`). `/nf requires` lists every route.',
  methods: false,
  fields: [],
  functions: [
    {
      name: 'route',
      doc: 'Answers requests for `method` at `path`, under the package\'s own `/<namespace>/`: `nf.web.route("POST", "/hooks/store", …)` in package `shop` answers `POST /shop/hooks/store`. A segment `:name` matches any one segment and hands it over as `request.params.name`; a last segment `*name` matches the rest of the path, slashes and all. `handler` is called with the request and a response to answer it with, now (`response:send`) or later, from a task or a callback (an `nf.http.request`, a database query); one not answered within `web.handler-timeout-seconds` is answered `504`. A `GET` route answers `HEAD` too. The route belongs to the script that made it and goes when it unloads; requests still waiting then are answered `503`. Two routes of one package for the same method and path, a path that isn\'t one, and a path a login (`nf.web.oauth`) uses are errors.',
      requires: 'web',
      params: [
        { name: 'method', type: '"GET"|"POST"|"PUT"|"PATCH"|"DELETE"', doc: 'The HTTP method.' },
        {
          name: 'path',
          type: 'string',
          doc: 'Where, under the package\'s own path: `/` and segments of letters, digits, `-`, `_`, `.` and `~`, `:name` or a last `*name`. `"/"` is the package\'s own path itself.',
        },
        {
          name: 'handler',
          type: 'fun(request: WebRequest, response: WebResponse)',
          doc: "Called on the main thread with the request and the response to answer it with. An error in it answers `500` and is reported as any handler's.",
        },
      ],
      returns: [{ type: 'Route', doc: 'The route: `route:remove()` stops answering.' }],
      example:
        'nf.web.route("GET", "/players/:name", function(request, response)\n  local player = nf.players.get(request.params.name)\n  if not player then\n    response:json(404, { error = "not online" })\n    return\n  end\n  response:json(200, { name = player:name(), health = player:health() })\nend)',
    },
    {
      name: 'url',
      doc: "The address the world reaches the calling package's `path` at: `web.public-url` in `config.yml` (the server's own address when it's empty), then `/<namespace>`, `path` and the query. For a link to put in chat, a webhook to register with another service, a login's address. `nil` while the server owner hasn't turned web routes on.",
      requires: 'web',
      params: [
        {
          name: 'path',
          type: 'string',
          doc: 'A path under the package\'s own, like a route\'s: `"/replays/12"`. Default `"/"`.',
          optional: true,
        },
        {
          name: 'query',
          type: 'table<string, string>',
          doc: 'Query parameters, encoded and sorted by name.',
          optional: true,
        },
      ],
      returns: [{ type: 'string?' }],
      example:
        'local link = nf.web.url("/replays/" .. replay_id)\nif link then\n  player:send_message("<click:open_url:" .. link .. ">Watch the replay</click>")\nend',
    },
    {
      name: 'verify_signature',
      doc: "Whether `signature` is the HMAC of `body` under `options.secret`, as webhook senders sign what they send (GitHub's `X-Hub-Signature-256`, Stripe's and Shopify's): compared in constant time, so the answer leaks nothing about the right one. Check it before acting on a webhook. Keep the secret in a setting (`nf.config`), not in a script. A signature that isn't in `options.encoding` is `false`, not an error. A sender that signs more than the body says what to check: Stripe signs `<t>.<body>`, its header's `t=` and the body, and sends the signature as `v1=` (and wants `t` checked to be recent). To make a signature, `nf.crypto.sign`.",
      params: [
        {
          name: 'body',
          type: 'string',
          doc: "The request's body exactly as it came: `request.body`.",
        },
        {
          name: 'signature',
          type: 'string',
          doc: 'The signature the sender sent, from its header.',
        },
        { name: 'options', type: 'SignatureOptions', doc: 'The secret, and how the sender signs.' },
      ],
      returns: [{ type: 'boolean' }],
      example:
        'nf.web.route("POST", "/hooks/github", function(request, response)\n  local signed = nf.web.verify_signature(request.body, request.headers["x-hub-signature-256"] or "", {\n    secret = nf.config("github_secret"),\n    prefix = "sha256=",\n  })\n  if not signed then\n    response:send(401)\n    return\n  end\n  response:send(204)\nend)',
    },
    {
      name: 'oauth',
      doc: 'A "log in with…" flow (OAuth 2 with PKCE, the authorization code grant): `flow:begin(player)` gives the address to send the player to, the service sends them back to the flow\'s own route (`/<namespace>/oauth/<name>/callback`, which you register with the service as its redirect URI: `nf.web.url("/oauth/<name>/callback")`), the browser is asked to confirm which Minecraft player it signs in as (`POST /<namespace>/oauth/<name>/confirm`, so a sign-in link passed to someone else can\'t link their account to the player who began), and the server then trades the code for a token itself and raises `complete` (or `fail`) on the flow. NetherForge keeps the state and the code verifier, ties each to the player who began, uses each once and lets it expire after 10 minutes, so a forged or replayed callback is refused. The token endpoint\'s host must be one the package declares in `requires.http`. The flow belongs to the script that made it and goes when it unloads; logins begun before a reload are forgotten. Two flows of one package with the same `name` are an error.',
      requires: 'web',
      params: [
        {
          name: 'options',
          type: 'OAuthOptions',
          doc: "The service's endpoints, the client's id and secret, and the scopes to ask for.",
        },
      ],
      returns: [{ type: 'OAuthFlow' }],
      example:
        'local discord = nf.web.oauth({\n  name = "discord",\n  authorize_url = "https://discord.com/oauth2/authorize",\n  token_url = "https://discord.com/api/oauth2/token",\n  client_id = nf.config("discord_client_id"),\n  client_secret = nf.config("discord_client_secret"),\n  scopes = { "identify" },\n})\ndiscord:on("complete", function(event)\n  event.player:send_message("<green>Linked your Discord account")\nend)\nnf.commands.register("link", function(event)\n  local url = event.player and discord:begin(event.player)\n  if url then\n    event.player:send_message("<click:open_url:" .. url .. "><u>Click to link Discord</u></click>")\n  end\nend)',
    },
  ],
}

/** A route, from `nf.web.route`. */
export const routeClass: LuaClass = {
  name: 'Route',
  doc: 'A route a script answers, from `nf.web.route`. It goes when its script unloads or `remove` is called.',
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'remove',
      doc: 'Stops answering: later requests to it are `404`, and requests still waiting are answered `503`.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was already gone.' }],
    },
    {
      name: 'exists',
      doc: 'Whether it still answers.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

/** The response a handler answers a request with. */
export const webResponseClass: LuaClass = {
  name: 'WebResponse',
  doc: "How a route's handler answers its request: once, with `send`, `json` or `redirect`, during the handler or later (from a task, or a callback). Every answer also says `X-Content-Type-Options: nosniff`, and `Cache-Control: no-store` unless you set one. One not answered within `web.handler-timeout-seconds` is answered `504`; one whose route's script unloads first, `503`. Ephemeral: it can't be saved in a `data()` table.",
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'send',
      doc: 'Answers with `status`, `body` and `headers`. The `Content-Type` is `text/plain; charset=utf-8` unless `headers` sets one (`["content-type"] = "text/html; charset=utf-8"` for a page). A body over `web.max-response-bytes`, a status that isn\'t one, or a header that can\'t be sent (one the server sets, like `Content-Length`) is an error at the script\'s line.',
      params: [
        {
          name: 'status',
          type: 'integer',
          doc: 'The status code, 100 to 599: `200`, `204`, `404`.',
        },
        {
          name: 'body',
          type: 'string',
          doc: 'The body, as text (UTF-8). Default empty.',
          optional: true,
        },
        {
          name: 'headers',
          type: 'table<string, string>',
          doc: 'Response headers by name.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`false` when it was already answered (or timed out), so nothing was sent.',
        },
      ],
      example:
        'response:send(200, "<h1>Hello</h1>", { ["content-type"] = "text/html; charset=utf-8" })',
    },
    {
      name: 'json',
      doc: 'Answers with `status` and `value` written as JSON (`application/json`), as `nf.json.encode` writes it.',
      params: [
        { name: 'status', type: 'integer', doc: 'The status code, 100 to 599.' },
        {
          name: 'value',
          type: 'any',
          doc: 'What to write: a table, a string, a number, a boolean.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` when it was already answered.' }],
      example: 'response:json(200, { online = #nf.players.online() })',
    },
    {
      name: 'redirect',
      doc: 'Answers with a redirect to `url` (`302`, or `status`: `301`, `303`, `307` or `308`).',
      params: [
        {
          name: 'url',
          type: 'string',
          doc: 'Where to: an absolute URL, or a path on this server.',
        },
        { name: 'status', type: 'integer', doc: 'Default `302`.', optional: true },
      ],
      returns: [{ type: 'boolean', doc: '`false` when it was already answered.' }],
    },
    {
      name: 'is_pending',
      doc: "Whether it still waits for an answer: `false` once it's answered, timed out, or its route's script unloaded.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

const oauthEvents: EventSpec[] = [
  {
    name: 'complete',
    doc: 'A player finished logging in: the service sent them back, and the server traded the code for a token. Their browser is shown a page saying they can go back to the game.',
    payload: 'OAuthCompleteEvent',
  },
  {
    name: 'fail',
    doc: "A player's login didn't finish: they declined, the service answered with an error, the token couldn't be had, or the login expired (10 minutes after `begin`). Their browser is shown a page saying so. A callback that matches no login of this flow (forged, or used twice) is refused with no event.",
    payload: 'OAuthFailEvent',
  },
]

/** A login flow, from `nf.web.oauth`. */
export const oauthFlowClass: LuaClass = {
  name: 'OAuthFlow',
  doc: 'A "log in with…" flow, from `nf.web.oauth`: `begin` a login for a player, and hear its `complete` or `fail`. It belongs to the script that made it.',
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  events: oauthEvents,
  functions: [
    {
      name: 'begin',
      doc: "Begins a login for `player`: the address of the service's page to send them to (as a link in chat), with a new state and code challenge that only this login's callback can use, for 10 minutes. `nil` while the server owner hasn't turned web routes on, or once the flow has gone.",
      params: [
        {
          name: 'player',
          type: 'Player',
          doc: 'Who logs in: their `complete` or `fail` names them.',
        },
      ],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'remove',
      doc: 'Ends the flow: its callback route goes and logins under way are forgotten.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was already gone.' }],
    },
    ...eventFunctions(
      'OAuthFlow',
      'flow',
      'flow:on("fail", function(event)\n  event.player:send_message("<red>Linking failed: " .. event.error)\nend)',
    ),
  ],
}

/** The tables `nf.web` takes and gives. */
export const webShapes: LuaClass[] = [
  shape('WebRequest', 'A request a route answers.', [
    {
      name: 'method',
      type: 'string',
      doc: '`"GET"`, `"POST"`, … (`"HEAD"` for a `GET` route asked only for headers).',
    },
    {
      name: 'path',
      type: 'string',
      doc: 'The path asked for, namespace and all: `"/shop/replays/12"`.',
    },
    {
      name: 'params',
      type: 'table<string, string>',
      doc: "The route's `:name` and `*name` segments by name, decoded.",
    },
    {
      name: 'query',
      type: 'table<string, string>',
      doc: 'The query parameters by name, decoded. A name given more than once is its first value.',
    },
    {
      name: 'headers',
      type: 'table<string, string>',
      doc: 'The request headers by name in lowercase; one sent more than once is its values joined with `, `.',
    },
    {
      name: 'body',
      type: 'string',
      doc: 'The body as text (UTF-8), at most `web.max-body-bytes`; `""` for none.',
    },
    {
      name: 'form',
      type: 'table<string, string>?',
      doc: "The body's fields, decoded, when it's a form (`application/x-www-form-urlencoded`); `nil` otherwise.",
    },
    {
      name: 'ip',
      type: 'string',
      doc: "The address it came from. Behind a reverse proxy that's the proxy's, unless the owner set `web.trust-proxy-headers`, which makes it the client's from `X-Forwarded-For`.",
    },
  ]),
  shape('SignatureOptions', 'How `nf.web.verify_signature` checks a signature.', [
    { name: 'secret', type: 'string', doc: 'The secret the sender signs with.' },
    {
      name: 'algorithm',
      type: '"sha256"|"sha1"|"sha512"?',
      doc: 'The HMAC\'s hash. Default `"sha256"`.',
    },
    {
      name: 'encoding',
      type: '"hex"|"base64"?',
      doc: 'How the signature is written. Default `"hex"` (either case).',
    },
    {
      name: 'prefix',
      type: 'string?',
      doc: 'Text before the signature to expect and skip, like `"sha256="`.',
    },
  ]),
  shape('OAuthOptions', 'What `nf.web.oauth` takes.', [
    {
      name: 'name',
      type: 'string',
      doc: "The flow's name, unique in the package: lowercase letters, digits, `-` and `_`. Its callback is `/<namespace>/oauth/<name>/callback`, and its confirmation `/<namespace>/oauth/<name>/confirm`.",
    },
    {
      name: 'authorize_url',
      type: 'string',
      doc: "The service's authorization page: an `https://` URL.",
    },
    {
      name: 'token_url',
      type: 'string',
      doc: "The service's token endpoint: an `https://` URL whose host the package declares in `requires.http`.",
    },
    { name: 'client_id', type: 'string', doc: 'The client id the service gave you.' },
    {
      name: 'client_secret',
      type: 'string?',
      doc: 'The client secret, for a service that wants one besides PKCE. Keep it in a setting (`nf.config`).',
    },
    { name: 'scopes', type: 'string[]?', doc: 'The scopes to ask for, sent separated by spaces.' },
    {
      name: 'client_auth',
      type: '"body"|"basic"?',
      doc: 'How the secret is sent to the token endpoint: in the form (`"body"`, the default) or as HTTP Basic (`"basic"`).',
    },
    {
      name: 'parameters',
      type: 'table<string, string>?',
      doc: 'More query parameters for the authorization page, like `{ prompt = "consent" }`.',
    },
  ]),
  shape(
    'OAuthCompleteEvent',
    'A login finished.',
    [
      { name: 'flow', type: 'OAuthFlow', doc: 'The flow.' },
      { name: 'player', type: 'Player', doc: 'Who logged in (perhaps offline by now).' },
      {
        name: 'access_token',
        type: 'string',
        doc: 'The access token, to call the service as them.',
      },
      { name: 'token_type', type: 'string?', doc: 'Its type, usually `"Bearer"`.' },
      { name: 'refresh_token', type: 'string?', doc: 'A refresh token, if the service gave one.' },
      {
        name: 'expires_in',
        type: 'integer?',
        doc: 'How many seconds the access token lasts, if the service said.',
      },
      { name: 'scope', type: 'string?', doc: 'The scopes granted, if the service said.' },
      {
        name: 'id_token',
        type: 'string?',
        doc: 'An OpenID Connect ID token, if the service gave one.',
      },
    ],
    'Event',
  ),
  shape(
    'OAuthFailEvent',
    "A login didn't finish.",
    [
      { name: 'flow', type: 'OAuthFlow', doc: 'The flow.' },
      { name: 'player', type: 'Player', doc: 'Who was logging in.' },
      { name: 'error', type: 'string', doc: 'What went wrong, in words.' },
    ],
    'Event',
  ),
]
