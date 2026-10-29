import type { Field, LuaClass } from '../types.ts'

const shape = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

const TEXT_ONLY =
  "Lua strings cross as text (UTF-8), as `nf.http`'s bodies do, so what's decoded must be text too: decoding to bytes that aren't UTF-8 (an image, a raw key) is `nil`."

const ALGORITHM: Field = {
  name: 'algorithm',
  type: '"sha256"|"sha1"|"sha512"?',
  doc: 'The hash. Default `"sha256"`.',
}

const ENCODING: Field = {
  name: 'encoding',
  type: '"hex"|"base64"?',
  doc: 'How the result is written: `"hex"` (lowercase), or `"base64"`. Default `"hex"`.',
}

/** `nf.encoding`: text written the ways web services want it. */
export const nfEncoding: LuaClass = {
  name: 'nf.encoding',
  doc: `Text written the ways web services want it: in a URL, as a form, as base64 or as hex. For building what \`nf.http.request\` sends and reading what comes back (\`nf.web\` already decodes a request's \`query\` and \`form\`). ${TEXT_ONLY}`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'url_encode',
      doc: 'Text made safe to put in one part of a URL (a path segment, a query value): every character but the letters, digits and `-`, `.`, `_` and `~` is written as its UTF-8 bytes in `%XX`, so a space is `%20` and `/` is `%2F`.',
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'string' }],
      example:
        'local url = "https://api.example.com/players/" .. nf.encoding.url_encode(player:name())',
    },
    {
      name: 'url_decode',
      doc: "Text with its `%XX` escapes decoded, the other way from `url_encode`. A `+` stays a `+` (in a form it's a space: `form_decode` reads those). `nil` for a `%` that isn't followed by two hex digits.",
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'form_encode',
      doc: 'A table as a form body (`application/x-www-form-urlencoded`), the way many APIs take a `POST`: `name=value` pairs joined by `&`, names in order, spaces as `+`. Values are strings, numbers and booleans (`true`, `false`). A table inside is written with brackets, as Stripe and other APIs read it: `{ metadata = { player = "Steve" } }` is `metadata[player]=Steve`, and a list counts from 0, `{ items = { "a", "b" } }` being `items[0]=a&items[1]=b` (the brackets escaped, `%5B` and `%5D`, as a form escapes them). An empty table inside adds nothing. A table at the top must be names, not a list. A value that can\'t be in a form (a function, a `Vec3`, a handle) is an error naming where it was.',
      params: [{ name: 'fields', type: 'table', doc: 'The names and their values.' }],
      returns: [{ type: 'string' }],
      example:
        'local body = nf.encoding.form_encode({\n  mode = "payment",\n  success_url = "https://example.com/thanks",\n  line_items = { { price = "price_123", quantity = 1 } },\n  metadata = { player = player:id() },\n})\n-- Decoded: line_items[0][price]=price_123&line_items[0][quantity]=1&metadata[player]=…&mode=payment&success_url=…',
    },
    {
      name: 'form_decode',
      doc: "A form body (`application/x-www-form-urlencoded`) as a table of its names and values, both decoded, as `nf.web` reads a request's: a `+` is a space, and a name given more than once is its first value. Brackets are left in names as they are (`metadata[player]`). `nil` for text that isn't a form (a `%` without two hex digits after it).",
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'table<string, string>?' }],
      example:
        'local fields = nf.encoding.form_decode("access_token=abc&token_type=bearer")\nif fields then\n  log(fields.access_token)\nend',
    },
    {
      name: 'base64_encode',
      doc: 'Text\'s UTF-8 bytes as base64: what `Authorization: Basic` wants (`"user:password"`), and what some services put in a header or a token.',
      params: [
        { name: 'text', type: 'string', doc: '' },
        {
          name: 'options',
          type: 'Base64Options',
          doc: 'Which alphabet, and whether to pad.',
          optional: true,
        },
      ],
      returns: [{ type: 'string' }],
      example:
        'local headers = {\n  Authorization = "Basic " .. nf.encoding.base64_encode(nf.config("api_user") .. ":" .. nf.config("api_key")),\n}',
    },
    {
      name: 'base64_decode',
      doc: `Base64 decoded back to text. Both alphabets are read (\`+/\` and the URL-safe \`-_\`), with or without padding. \`nil\` for text that isn't base64, and for bytes that aren't text.`,
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'hex_encode',
      doc: 'Text\'s UTF-8 bytes as hex, two lowercase digits a byte: `"Hi"` is `"4869"`.',
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'string' }],
    },
    {
      name: 'hex_decode',
      doc: "Hex (either case) decoded back to text. `nil` for text that isn't hex (an odd number of digits, a letter past `f`), and for bytes that aren't text.",
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'string?' }],
    },
  ],
}

/** `nf.crypto`: hashes and signatures. */
export const nfCrypto: LuaClass = {
  name: 'nf.crypto',
  doc: "Hashes and signatures (HMAC), for services that sign what they send or want what's sent to them signed. Checking a webhook's signature is `nf.web.verify_signature`, which compares in constant time. Keep secrets in a setting (`nf.config`), not in a script. Both work on the text's UTF-8 bytes.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'hash',
      doc: 'A hash of text: the same text always has the same hash, and nobody can work the text back out of it. For checking that something arrived unchanged, or for a service that asks for one; not for passwords, which need a slow hash made for them.',
      params: [
        { name: 'text', type: 'string', doc: '' },
        {
          name: 'options',
          type: 'HashOptions',
          doc: 'Which hash, and how to write it.',
          optional: true,
        },
      ],
      returns: [{ type: 'string' }],
      example: 'log(nf.crypto.hash("Hello, World!")) -- dffd6021bb2bd5b0…',
    },
    {
      name: 'sign',
      doc: "Text's HMAC under a secret: the signature a service checks to know a request came from someone who has the secret, the same one `nf.web.verify_signature` checks.",
      params: [
        { name: 'text', type: 'string', doc: 'What to sign, exactly as it will be sent.' },
        { name: 'options', type: 'SigningOptions', doc: 'The secret, and how to sign.' },
      ],
      returns: [{ type: 'string' }],
      example:
        'local body = nf.json.encode({ player = player:id(), product = "vip" })\nlocal signature = nf.crypto.sign(body, { secret = nf.config("shop_secret") })\nnf.http.request({\n  url = "https://shop.example.com/orders",\n  method = "POST",\n  headers = { ["Content-Type"] = "application/json", ["X-Signature"] = signature },\n  body = body,\n}, function(response, err) end)',
    },
  ],
}

/** The plain tables `nf.encoding` and `nf.crypto` take. */
export const encodingShapes: LuaClass[] = [
  shape(
    'Base64Options',
    "How `nf.encoding.base64_encode` writes base64. A key that isn't one of these is an error.",
    [
      {
        name: 'url_safe',
        type: 'boolean?',
        doc: 'Whether to use the URL-safe alphabet (`-` and `_` for `+` and `/`), for a URL or a token. Default `false`.',
      },
      {
        name: 'padding',
        type: 'boolean?',
        doc: 'Whether to end with `=` to a multiple of four characters. Default `true`.',
      },
    ],
  ),
  shape('HashOptions', "How `nf.crypto.hash` hashes. A key that isn't one of these is an error.", [
    ALGORITHM,
    ENCODING,
  ]),
  shape(
    'SigningOptions',
    "How `nf.crypto.sign` signs. A key that isn't one of these is an error.",
    [{ name: 'secret', type: 'string', doc: 'The secret to sign with.' }, ALGORITHM, ENCODING],
  ),
]
