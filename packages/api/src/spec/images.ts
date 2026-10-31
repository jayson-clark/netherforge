import type { LuaClass } from '../types.ts'
import { asyncFunction } from '../async.ts'

const LIMITS =
  'An image is at most 2048 pixels each way, and the images the server holds for scripts at once are at most 32 million pixels in all; one past that is an error, so let go of images you no longer need (an image Lua no longer holds is let go of for you).'

/** `nf.images`: pictures made at runtime. */
export const nfImages: LuaClass = {
  name: 'nf.images',
  doc: `Pictures made at runtime, for image displays (\`node:set_image\`): from pixels, from an image file's bytes, or downloaded. An \`Image\` is a value that never changes: resizing or cropping one makes another. ${LIMITS}`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'create',
      doc: 'An image from its pixels, row by row from the top left: a list of colours (`"#RRGGBB"`, or `"#AARRGGBB"` for one you can see through), or every pixel\'s bytes in one base64 string, 3 a pixel (red, green, blue) or 4 (and alpha). The wrong number of pixels, or a colour that isn\'t one, is an error.',
      params: [
        { name: 'width', type: 'integer', doc: 'In pixels, 1 to 2048.' },
        { name: 'height', type: 'integer', doc: 'In pixels, 1 to 2048.' },
        { name: 'pixels', type: 'string[]|string', doc: 'Colours, or base64 bytes.' },
      ],
      returns: [{ type: 'Image' }],
      example:
        'local flag = nf.images.create(3, 1, { "#0055A4", "#FFFFFF", "#EF4135" })\nassert(this:node("flag")):set_image(flag)',
    },
    {
      name: 'fill',
      doc: 'An image all one colour.',
      params: [
        { name: 'width', type: 'integer', doc: 'In pixels, 1 to 2048.' },
        { name: 'height', type: 'integer', doc: 'In pixels, 1 to 2048.' },
        { name: 'color', type: 'string', doc: '`"#RRGGBB"` or `"#AARRGGBB"`.' },
      ],
      returns: [{ type: 'Image' }],
    },
    {
      name: 'decode',
      doc: "An image from an image file's bytes (PNG, JPEG, GIF or BMP) in base64, as a catalog, a database or `image:encode` keeps them. `nil` for bytes that aren't an image this can read, or one bigger than 2048 pixels either way.",
      params: [{ name: 'data', type: 'string', doc: 'The file, base64.' }],
      returns: [{ type: 'Image?' }],
    },
    asyncFunction({
      name: 'download',
      doc: "Downloads an image file (PNG, JPEG, GIF or BMP) and gives it as an image: `nf.http.request`'s request, held to the same rules (the host declared in `requires.http`, the size limit, the rate), its body read as the image it is rather than as text. A response that isn't `2xx`, or isn't an image this can read, fails.",
      params: [
        {
          name: 'options',
          type: 'HttpRequestOptions',
          doc: 'The request, as `nf.http.request` takes it: usually just its `url`.',
        },
      ],
      value: { name: 'image', type: 'Image', doc: 'the picture' },
      requires: 'http',
      example:
        'nf.task(function()\n  local image, err = nf.images.download({ url = "https://cdn.example.com/thumbs/42.png" })\n  if image then\n    assert(this:node("thumbnail")):set_image(image:resized(96, 54))\n  else\n    log("no thumbnail: " .. err)\n  end\nend)',
    }),
  ],
}

/** `Image`: a picture `nf.images` made. */
export const imageClass: LuaClass = {
  name: 'Image',
  doc: `A picture made at runtime by \`nf.images\`, for image displays (\`node:set_image\`). It never changes: \`resized\` and \`cropped\` make new ones. Ephemeral: it can't be saved in a \`data()\` table (keep \`image:encode()\` instead). ${LIMITS}`,
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'width',
      doc: 'Its width in pixels.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'height',
      doc: 'Its height in pixels.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'pixel',
      doc: 'The colour of the pixel at column `x`, row `y` (both from 0, at the top left): `"#RRGGBB"`, or `"#AARRGGBB"` when it isn\'t opaque. `nil` outside the image.',
      params: [
        { name: 'x', type: 'integer', doc: '' },
        { name: 'y', type: 'integer', doc: '' },
      ],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'resized',
      doc: "A copy `width` by `height` pixels: each new pixel the average of the old ones it covers, so shrinking is smooth. An image display draws an image's pixels one for one, so resize one to the display's `width` and `height` first.",
      params: [
        { name: 'width', type: 'integer', doc: 'In pixels, 1 to 2048.' },
        { name: 'height', type: 'integer', doc: 'In pixels, 1 to 2048.' },
      ],
      returns: [{ type: 'Image' }],
    },
    {
      name: 'cropped',
      doc: "The part `width` by `height` pixels whose top left is at column `x`, row `y`. `nil` when that isn't all inside the image.",
      params: [
        { name: 'x', type: 'integer', doc: '' },
        { name: 'y', type: 'integer', doc: '' },
        { name: 'width', type: 'integer', doc: '' },
        { name: 'height', type: 'integer', doc: '' },
      ],
      returns: [{ type: 'Image?' }],
    },
    {
      name: 'encode',
      doc: 'The image as a PNG file, base64: what `nf.images.decode` reads back, to keep in a database, a network map or saved data.',
      params: [],
      returns: [{ type: 'string' }],
    },
  ],
}

/** The shapes `nf.text.measure` takes and gives. */
export const imageShapes: LuaClass[] = [
  {
    name: 'TextMeasureOptions',
    doc: "How `nf.text.measure` measures. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'scale',
        type: 'number?',
        doc: "The text display's scale (its node's, and its parents'). Default 1.",
      },
    ],
  },
  {
    name: 'TextSize',
    doc: 'How big text draws on a text display, in blocks: what an `anchor`ed text display is placed by.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'width',
        type: 'number',
        doc: 'Its widest line: every character and the gap after it.',
      },
      { name: 'height', type: 'number', doc: 'Its lines, a quarter of a block each at scale 1.' },
    ],
  },
]
