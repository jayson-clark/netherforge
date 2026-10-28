import type { EventSpec, Fn, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const COLOR =
  'A colour, `#rrggbb` (drawn as the nearest of the colours a map can show), or `"clear"` for none: the map shows through.'

const GONE = 'Nothing happens once the screen is gone.'

const xy = (what: string) => [
  { name: 'x', type: 'integer', doc: `${what}'s left, in pixels from the screen's left edge.` },
  { name: 'y', type: 'integer', doc: `${what}'s top, in pixels from the screen's top edge.` },
]

/** `nf.screens`: walls of maps scripts draw on. */
export const nfScreens: LuaClass = {
  name: 'nf.screens',
  doc: "Screens: walls of maps, 128 by 128 pixels each, that scripts draw on (pixels, shapes, text, pictures) and play videos on. The project's own are its `screens/<id>/screen.json` files; a script can make more. What a screen shows is sent to each player who can see it, a change at a time, so drawing every tick costs only what changed.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'get',
      doc: "One of the project's screens (`screens/<id>/screen.json`). A screen the project doesn't have, or one whose file has errors and never had a good version, is an error.",
      params: [
        {
          name: 'screen',
          type: 'string',
          doc: 'Its folder name under `screens/`.',
          names: 'screen',
        },
      ],
      returns: [{ type: 'Screen' }],
      example: 'local board = nf.screens.get("lobby_board")\nboard:clear("#101018")',
    },
    {
      name: 'all',
      doc: "Every one of the project's screens, and every screen a script made that's still there.",
      params: [],
      returns: [{ type: 'Screen[]' }],
    },
    {
      name: 'create',
      doc: "Makes a screen. It belongs to the script that made it: when that script unloads, the screen goes, its frames taken down. With a `key`, the screen's maps keep their numbers for good (a map item handed out, or a frame someone hung, still shows it after a restart, once a script makes it again with the same key); without one, they're new each time. A size outside 1 to 16 maps is an error.",
      params: [{ name: 'options', type: 'ScreenOptions', doc: 'Its size, and how it shows.' }],
      returns: [{ type: 'Screen' }],
      example:
        'local scores = nf.screens.create({ width = 3, height = 2, key = "scores" })\nscores:place(nf.worlds.default():location(vec3(10, 70, -4)), "south")\nscores:text(8, 8, "<gold>Top players")',
    },
    {
      name: 'fonts',
      doc: "The fonts text can be drawn in: `default` (the game's own map font) and every font of the project's (`screen_fonts/<id>/`).",
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'measure_text',
      doc: 'How big `text` would be drawn with `options`, in pixels, without drawing it. An unknown font is an error.',
      params: [
        {
          name: 'text',
          type: 'Text',
          doc: 'MiniMessage: colours and `<b>`, `<u>`, `<st>` are drawn.',
        },
        {
          name: 'options',
          type: 'ScreenTextOptions',
          doc: 'How it would be drawn.',
          optional: true,
        },
      ],
      returns: [{ type: 'ScreenTextSize' }],
    },
    {
      name: 'map_color',
      doc: 'The colour a map would really draw for `color`: the nearest it can show, as `#rrggbb`, or `"clear"` for `"clear"`. A colour that isn\'t written `#rrggbb` is an error.',
      params: [{ name: 'color', type: 'string', doc: COLOR }],
      returns: [{ type: 'string' }],
      example: 'print(nf.screens.map_color("#ff8800")) -- the orange a map really shows',
    },
    {
      name: 'looking_at',
      doc: 'The screen a player is looking at and the pixel at the middle of their view, within `distance` blocks; `nil` when they look at none, or something solid is in the way. Screens hidden from the player are looked through.',
      params: [
        { name: 'player', type: 'Player', doc: 'Who.' },
        {
          name: 'distance',
          type: 'number',
          doc: 'How far to look, in blocks (default 64).',
          optional: true,
        },
      ],
      returns: [{ type: 'ScreenPoint?' }],
    },
  ],
}

const screenEvents: EventSpec[] = [
  {
    name: 'click',
    doc: "A player clicked the screen: `event.x` and `event.y` are the pixel they aimed at. A click on one of its frames always counts; from further than the game's reach, a swing of the arm aimed at the screen does, up to its click range. Only players it's drawn for can click it. The game's own use of the click (breaking, turning the map) never happens.",
    payload: 'ScreenClickEvent',
    example:
      'this:on("click", function(event)\n  if event.x < 64 then\n    event.player:send_message("Left half!")\n  end\nend)',
  },
  {
    name: 'hover',
    doc: "The pixel a player is aiming at moved: heard each tick their aim lands on another pixel, within the screen's click range. Costs nothing while nobody listens.",
    payload: 'ScreenHoverEvent',
    example:
      'local cursor = {}\nthis:on("hover", function(event)\n  cursor[event.player:name()] = { event.x, event.y }\nend)',
  },
  {
    name: 'leave',
    doc: 'A player who was aiming at the screen (a `hover` was heard for them) looks away from it, goes out of range or leaves.',
    payload: 'ScreenLeaveEvent',
  },
]

const drawing: Fn[] = [
  {
    name: 'clear',
    doc: `Fills the whole screen with one colour. ${GONE}`,
    params: [
      { name: 'color', type: 'string', doc: `${COLOR} Default \`"clear"\`.`, optional: true },
    ],
    returns: [],
  },
  {
    name: 'pixel',
    doc: 'One pixel\'s colour, as the map draws it: `#rrggbb`, or `"clear"`. `nil` for a pixel off the screen, or once the screen is gone.',
    params: [
      { name: 'x', type: 'integer', doc: 'From the left edge, 0 first.' },
      { name: 'y', type: 'integer', doc: 'From the top edge, 0 first.' },
    ],
    returns: [{ type: 'string?' }],
  },
  {
    name: 'set_pixel',
    doc: `Sets one pixel. One off the screen is left alone. ${GONE}`,
    params: [
      { name: 'x', type: 'integer', doc: 'From the left edge, 0 first.' },
      { name: 'y', type: 'integer', doc: 'From the top edge, 0 first.' },
      { name: 'color', type: 'string?', doc: `${COLOR} \`nil\` is \`"clear"\`.` },
    ],
    returns: [],
  },
  {
    name: 'pixels',
    doc: 'A block of pixels\' colours, row by row (`#rrggbb`, or `"clear"`): what `set_pixels` takes. Pixels off the screen are `"clear"`; an empty list once the screen is gone. A block of more than 65536 pixels is an error.',
    params: [
      ...xy('The block'),
      { name: 'width', type: 'integer', doc: 'In pixels.' },
      { name: 'height', type: 'integer', doc: 'In pixels.' },
    ],
    returns: [{ type: 'string[]' }],
  },
  {
    name: 'set_pixels',
    doc: `Sets a block of pixels from a list of colours, row by row, \`width\` to a row: the quick way to draw many pixels at once. What's off the screen is left alone. ${GONE}`,
    params: [
      ...xy('The block'),
      { name: 'width', type: 'integer', doc: 'Pixels in a row of `colors`.' },
      { name: 'colors', type: 'string[]', doc: `Each pixel's colour. ${COLOR}` },
    ],
    returns: [],
    example:
      'local row = {}\nfor i = 1, 128 do row[i] = i % 2 == 0 and "#ffffff" or "#000000" end\nthis:set_pixels(0, 0, 128, row)',
  },
  {
    name: 'fill',
    doc: `Fills a rectangle with one colour. ${GONE}`,
    params: [
      ...xy('The rectangle'),
      { name: 'width', type: 'integer', doc: 'In pixels.' },
      { name: 'height', type: 'integer', doc: 'In pixels.' },
      { name: 'color', type: 'string', doc: COLOR },
    ],
    returns: [],
  },
  {
    name: 'rectangle',
    doc: `Draws a rectangle: filled, outlined, or both, with rounded corners if \`style.radius\` says. ${GONE}`,
    params: [
      ...xy('The rectangle'),
      { name: 'width', type: 'integer', doc: 'In pixels.' },
      { name: 'height', type: 'integer', doc: 'In pixels.' },
      { name: 'style', type: 'ShapeStyle', doc: 'Its fill and outline.' },
    ],
    returns: [],
    example:
      'this:rectangle(8, 8, 112, 40, { fill = "#202040", outline = "#ffcc00", thickness = 2, radius = 6 })',
  },
  {
    name: 'line',
    doc: `Draws a straight line between two pixels, both included. ${GONE}`,
    params: [
      { name: 'x1', type: 'integer', doc: 'Where it starts.' },
      { name: 'y1', type: 'integer', doc: '' },
      { name: 'x2', type: 'integer', doc: 'Where it ends.' },
      { name: 'y2', type: 'integer', doc: '' },
      { name: 'color', type: 'string', doc: COLOR },
      {
        name: 'thickness',
        type: 'integer',
        doc: 'In pixels, 1 to 32 (default 1).',
        optional: true,
      },
    ],
    returns: [],
  },
  {
    name: 'circle',
    doc: `Draws a circle round a pixel: filled, outlined, or both. ${GONE}`,
    params: [
      { name: 'x', type: 'integer', doc: 'Its middle, from the left edge.' },
      { name: 'y', type: 'integer', doc: 'Its middle, from the top edge.' },
      { name: 'radius', type: 'integer', doc: 'In pixels, 0 or more.' },
      { name: 'style', type: 'ShapeStyle', doc: 'Its fill and outline (`radius` is not used).' },
    ],
    returns: [],
  },
  {
    name: 'ellipse',
    doc: `Draws an ellipse round a pixel. ${GONE}`,
    params: [
      { name: 'x', type: 'integer', doc: 'Its middle, from the left edge.' },
      { name: 'y', type: 'integer', doc: 'Its middle, from the top edge.' },
      { name: 'radius_x', type: 'integer', doc: 'Across, in pixels.' },
      { name: 'radius_y', type: 'integer', doc: 'Down, in pixels.' },
      { name: 'style', type: 'ShapeStyle', doc: 'Its fill and outline (`radius` is not used).' },
    ],
    returns: [],
  },
  {
    name: 'polygon',
    doc: `Draws a closed shape through points, \`{ x1, y1, x2, y2, … }\` (at least three points). Fewer than three, or an odd count of numbers, is an error. ${GONE}`,
    params: [
      { name: 'points', type: 'integer[]', doc: 'The corners, each an x then a y.' },
      { name: 'style', type: 'ShapeStyle', doc: 'Its fill and outline (`radius` is not used).' },
    ],
    returns: [],
    example: 'this:polygon({ 64, 8, 120, 120, 8, 120 }, { fill = "#44aa44" })',
  },
  {
    name: 'text',
    doc: `Writes text with its top left at a pixel. Lines break at \`\\n\`, and at spaces past \`options.width\` when it's given. MiniMessage colours, \`<b>\`, \`<u>\` and \`<st>\` are drawn; glyphs and other tags aren't. An unknown font is an error. Returns how big it was, as \`nf.screens.measure_text\` would say (\`0\` by \`0\` once the screen is gone).`,
    params: [
      ...xy('The text'),
      { name: 'text', type: 'Text', doc: 'What to write.' },
      {
        name: 'options',
        type: 'ScreenTextOptions',
        doc: 'Font, colour, size, alignment.',
        optional: true,
      },
    ],
    returns: [{ type: 'ScreenTextSize' }],
    example:
      'this:text(64, 4, "<gold>Lobby</gold>\\n<gray>3 players", { align = "center", scale = 2 })',
  },
  {
    name: 'image',
    doc: `Draws a picture: a texture of a resource pack, as \`<pack>/<path>\` (\`ui/logo\` is \`resource_packs/ui/textures/logo.png\`). Its colours become map colours as \`options.dither\` says; pixels less than half opaque draw nothing. A picture the project doesn't have is an error. Returns \`false\` when the picture can't be read. ${GONE}`,
    params: [
      ...xy('The picture'),
      { name: 'image', type: 'string', doc: 'The texture, `<pack>/<path>` without `.png`.' },
      {
        name: 'options',
        type: 'ScreenImageOptions',
        doc: 'Its size and dithering.',
        optional: true,
      },
    ],
    returns: [{ type: 'boolean' }],
    example: 'this:image(0, 0, "ui/logo", { width = 256, height = 128 })',
  },
  {
    name: 'copy',
    doc: `Copies a rectangle of the screen to another place on it (overlapping is fine): a quick way to scroll. What's off the screen is left alone. ${GONE}`,
    params: [
      ...xy('The rectangle copied'),
      { name: 'width', type: 'integer', doc: 'In pixels.' },
      { name: 'height', type: 'integer', doc: 'In pixels.' },
      { name: 'to_x', type: 'integer', doc: 'Where its left goes.' },
      { name: 'to_y', type: 'integer', doc: 'Where its top goes.' },
    ],
    returns: [],
    example:
      '-- Scroll everything up by 10 pixels.\nthis:copy(0, 10, this:width(), this:height() - 10, 0, 0)',
  },
]

/** A wall of maps. */
export const screenClass: LuaClass = {
  name: 'Screen',
  doc: "A screen: maps (each 128 by 128 pixels) side by side, which scripts draw on and play videos on, shown in item frames (`place`, or the file's `place`) or as map items in players' hands (`item`). Pixel `0, 0` is its top left. What it shows is the server's to send and isn't saved: draw it whenever its script starts. Once a screen is gone (a script's screen whose script unloaded, a project screen whose file was deleted) its drawing does nothing and its getters answer `nil` or `false`.\n\nA screen is drawn for every player near it (`viewers = \"near\"`), or only for those a script shows it to. `for_player` gives a player a view of their own: the same maps, drawn differently for them alone.",
  methods: true,
  handle: {
    key: [
      { name: 'id', type: 'string' },
      { name: 'player', type: 'string' },
    ],
  },
  fields: [],
  events: screenEvents,
  functions: [
    {
      name: 'name',
      doc: "The project's screen it is (its folder name under `screens/`), or the `key` a script made it with; `nil` for a script's screen made without one.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'exists',
      doc: "Whether it's still there.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'width',
      doc: 'How wide it is, in pixels: 128 a map.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'height',
      doc: 'How tall it is, in pixels: 128 a map.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    ...drawing,
    {
      name: 'place',
      doc: "Hangs it on a wall: an invisible, fixed item frame a map, its top left map at `location`'s block (the air in front of the wall) and the rest going right and down as a player facing it sees them. The frames need no wall behind them. A screen already hanging somewhere is moved. A project screen hangs where its file says again when the server restarts. Returns `false` when the world isn't loaded.",
      params: [
        { name: 'location', type: 'Location', doc: 'Where its top left map goes.' },
        {
          name: 'facing',
          type: '"north"|"south"|"east"|"west"',
          doc: 'The way it faces: out of the wall, towards whoever looks at it.',
        },
      ],
      returns: [{ type: 'boolean' }],
      example: 'this:place(nf.worlds.default():location(vec3(0, 72, 0)), "south")',
    },
    {
      name: 'take_down',
      doc: 'Takes its frames down. It can still be drawn on, and its map items still show it.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it hung nowhere.' }],
    },
    {
      name: 'location',
      doc: 'Where its top left map hangs; `nil` when it hangs nowhere.',
      params: [],
      returns: [{ type: 'Location?' }],
    },
    {
      name: 'facing',
      doc: 'Which way it faces where it hangs; `nil` when it hangs nowhere.',
      params: [],
      returns: [{ type: '("north"|"south"|"east"|"west")?' }],
    },
    {
      name: 'item',
      doc: "A map item showing one of its maps, to give a player or put in a frame: `column` and `row` count from 0 at its top left. A map off the screen is an error. A screen made without a `key` gets new map numbers each time it's made, so its items show nothing after a restart.",
      params: [
        {
          name: 'column',
          type: 'integer',
          doc: 'Which map across, 0 first (default 0).',
          optional: true,
        },
        {
          name: 'row',
          type: 'integer',
          doc: 'Which map down, 0 first (default 0).',
          optional: true,
        },
      ],
      returns: [{ type: 'Item' }],
      example: 'player:give_item(this:item())',
    },
    {
      name: 'show_to',
      doc: 'Draws it for a player, whoever it\'s drawn for otherwise (a `viewers = "script"` screen is drawn only for those it\'s shown to). Lasts until `hide_from`, or the screen goes.',
      params: [{ name: 'player', type: 'Player', doc: 'Who.' }],
      returns: [{ type: 'boolean', doc: '`false` when they are offline or the screen is gone.' }],
    },
    {
      name: 'hide_from',
      doc: "Stops drawing it for a player (they see blank maps, and can't click it), until `show_to`.",
      params: [{ name: 'player', type: 'Player', doc: 'Who.' }],
      returns: [{ type: 'boolean', doc: '`false` when they are offline or the screen is gone.' }],
    },
    {
      name: 'is_shown_to',
      doc: 'Whether it is drawn for a player now: shown to them, or near them on a `"near"` screen, and not hidden from them.',
      params: [{ name: 'player', type: 'Player', doc: 'Who.' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'viewers',
      doc: 'Everyone it is drawn for now.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'for_player',
      doc: "A player's own view of the screen: drawing on it changes what they see alone (the same maps, the same frames), starting from what everyone sees now. It lasts until `release`, they leave, or the screen goes; drawing on the shared screen meanwhile doesn't reach them. Called on a player's own view, it's that view.",
      params: [{ name: 'player', type: 'Player', doc: 'Whose view.' }],
      returns: [{ type: 'Screen' }],
      example:
        'nf.on("player_join", function(event)\n  local mine = this:for_player(event.player)\n  mine:text(4, 4, "Hello, " .. event.player:name())\nend)',
    },
    {
      name: 'player',
      doc: 'Whose own view this is; `nil` for the screen everyone sees.',
      params: [],
      returns: [{ type: 'Player?' }],
    },
    {
      name: 'release',
      doc: "Ends a player's own view (see `for_player`): they see what everyone sees again.",
      params: [],
      returns: [
        { type: 'boolean', doc: "`false` when this isn't a player's own view, or it has ended." },
      ],
    },
    {
      name: 'play',
      doc: 'Plays a video (`videos/<id>/`) on the screen, from its top left (`options.x`, `options.y` move it), replacing any video playing on it (that one ends, reason `"replaced"`). Its sound plays to the players it\'s drawn for who are near where it hangs. It belongs to the script that played it: when that script unloads, it ends. A video the project doesn\'t have is an error; one not yet converted in the editor gives `nil`.',
      params: [
        {
          name: 'video',
          type: 'string',
          doc: 'Its folder name under `videos/`.',
          names: 'video',
        },
        { name: 'options', type: 'VideoPlayOptions', doc: 'How it plays.', optional: true },
      ],
      returns: [
        { type: 'Playback?', doc: "`nil` when it can't be played, or the screen is gone." },
      ],
      example:
        'local show = this:play("trailer", { loop = true })\nif show then\n  show:on("end", function(event)\n    log("trailer ended:", event.reason)\n  end)\nend',
    },
    {
      name: 'playback',
      doc: 'The video playing on it now; `nil` when none is.',
      params: [],
      returns: [{ type: 'Playback?' }],
    },
    {
      name: 'remove',
      doc: "Takes a script's screen away for good: its frames come down, and its video ends. A screen made with a `key` gives up its maps' numbers too: one made again with that key gets new ones, and an item or frame of the old ones shows nothing. A project screen can't be removed (its file says it's there): that's an error.",
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was already gone.' }],
    },
    ...eventFunctions(
      'Screen',
      'screen',
      'this:on("click", function(event)\n  this:circle(event.x, event.y, 3, { fill = "#ff4040" })\nend)',
    ),
  ],
}

const playbackEvents: EventSpec[] = [
  {
    name: 'end',
    doc: 'The video ended, for any reason (`event.reason` says which). After it, the playback is gone and so is every handler on it; the screen keeps showing its last frame.',
    payload: 'PlaybackEndEvent',
  },
  {
    name: 'frame',
    doc: "A frame of the video was drawn: as often as the video's rate while it plays. Costs nothing while nobody listens.",
    payload: 'PlaybackFrameEvent',
    example:
      'local show = this:play("trailer")\nif show then\n  show:on("frame", function(event)\n    if event.frame == 40 then\n      this:text(4, 4, "<yellow>Here it comes!")\n    end\n  end)\nend',
  },
]

/** A video playing on a screen. */
export const playbackClass: LuaClass = {
  name: 'Playback',
  doc: 'A video playing on a screen, from `screen:play`. Once it has ended its methods answer `nil` or `false`, except `video()` and `length()`; `exists()` says whether it has.',
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'video', type: 'string' },
    ],
  },
  fields: [],
  events: playbackEvents,
  functions: [
    {
      name: 'video',
      doc: 'Which video it is: its folder name under `videos/`, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'screen',
      doc: 'The screen it plays on; `nil` once it has ended.',
      params: [],
      returns: [{ type: 'Screen?' }],
    },
    {
      name: 'length',
      doc: 'How long the video lasts, in seconds, even after it has ended.',
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'exists',
      doc: "Whether it hasn't ended: paused counts.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_paused',
      doc: 'Whether it is paused.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'pause',
      doc: 'Holds it on the frame it shows, its sound stopped.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was paused already, or has ended.' }],
    },
    {
      name: 'resume',
      doc: 'Plays it on from where it was paused, its sound from there too.',
      params: [],
      returns: [{ type: 'boolean', doc: "`false` when it wasn't paused, or has ended." }],
    },
    {
      name: 'stop',
      doc: 'Ends it now, with reason `"stopped"`. The screen keeps its last frame.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    {
      name: 'time',
      doc: "Where it is, in seconds from the video's start; `nil` once it has ended.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'seek',
      doc: 'Jumps to a time in the video, in seconds (held to 0 and its length); its sound starts again from there.',
      params: [{ name: 'seconds', type: 'number', doc: 'Where to.' }],
      returns: [{ type: 'boolean', doc: '`false` when it has ended.' }],
    },
    {
      name: 'speed',
      doc: 'How fast it plays: 1 is as recorded. `nil` once it has ended.',
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_speed',
      doc: 'Plays it faster or slower, 0.25 to 4 (held to that). Its sound plays only at speed 1.',
      params: [{ name: 'speed', type: 'number?', doc: 'The new speed; `nil` is 1.' }],
      returns: [{ type: 'boolean', doc: '`false` when it has ended.' }],
    },
    {
      name: 'volume',
      doc: "Its sound's volume, 0 to 1; `nil` once it has ended.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_volume',
      doc: "Changes its sound's volume, 0 to 1 (held to that); its sound starts again at the new volume.",
      params: [{ name: 'volume', type: 'number?', doc: 'The new volume; `nil` is 1.' }],
      returns: [{ type: 'boolean', doc: '`false` when it has ended.' }],
    },
    {
      name: 'is_looping',
      doc: 'Whether it starts again when it reaches its end.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_looping',
      doc: 'Whether it starts again when it reaches its end.',
      params: [{ name: 'looping', type: 'boolean', doc: 'Loop or not.' }],
      returns: [{ type: 'boolean', doc: '`false` when it has ended.' }],
    },
    ...eventFunctions(
      'Playback',
      'playback',
      'local show = this:play("trailer")\nif show then\n  show:on("end", function(event)\n    log("trailer ended:", event.reason)\n  end)\nend',
    ),
  ],
}

/** The plain tables screens take and hand out. */
export const screenShapes: LuaClass[] = [
  {
    name: 'ScreenOptions',
    doc: "A screen `nf.screens.create` makes: `width` and `height` are required; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'width', type: 'integer', doc: 'Maps across, 1 to 16.' },
      { name: 'height', type: 'integer', doc: 'Maps down, 1 to 16.' },
      {
        name: 'key',
        type: 'string?',
        doc: "Keeps its maps' numbers for good, across restarts (see `nf.screens.create`): any name, unique among the screens this package's scripts make. Making one with a key a screen that's still there has is an error.",
      },
      {
        name: 'background',
        type: 'string?',
        doc: `What it starts filled with. ${COLOR} Default \`"clear"\`.`,
      },
      {
        name: 'viewers',
        type: '("near"|"script")?',
        doc: '`"near"` (the default): every player near where it hangs, or everyone online while it hangs nowhere. `"script"`: only those `show_to` shows it to.',
      },
      {
        name: 'range',
        type: 'integer?',
        doc: 'How near is near, in blocks: 1 to 256 (default 64).',
      },
      {
        name: 'glowing',
        type: 'boolean?',
        doc: 'Whether its frames glow, drawing it at full brightness (default true).',
      },
      {
        name: 'click_range',
        type: 'number?',
        doc: "How far a click reaches it from, in blocks: 0 to 64 (default 24). Within the game's reach a click on a frame always counts.",
      },
    ],
  },
  {
    name: 'ShapeStyle',
    doc: 'How a shape is drawn: `fill`, `outline`, or both (neither is an error).',
    methods: false,
    functions: [],
    fields: [
      { name: 'fill', type: 'string?', doc: `Its inside. ${COLOR}` },
      { name: 'outline', type: 'string?', doc: `Its edge. ${COLOR}` },
      {
        name: 'thickness',
        type: 'integer?',
        doc: "The outline's width, in pixels: 1 to 32 (default 1).",
      },
      {
        name: 'radius',
        type: 'integer?',
        doc: "A rectangle's rounded corners, in pixels (default 0).",
      },
    ],
  },
  {
    name: 'ScreenTextOptions',
    doc: 'How text is drawn on a screen. Every key is optional.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'font',
        type: 'string?',
        doc: '`"default"` (the game\'s own map font, the default) or one of the project\'s (`screen_fonts/<id>/`).',
      },
      {
        name: 'color',
        type: 'string?',
        doc: 'Its colour where the text sets none, `#rrggbb` (default white).',
      },
      {
        name: 'scale',
        type: 'integer?',
        doc: 'Each pixel of the font drawn this many pixels a side: 1 to 8 (default 1).',
      },
      {
        name: 'align',
        type: '("left"|"center"|"right")?',
        doc: 'Where `x` is: its left (the default), its middle or its right, line by line.',
      },
      {
        name: 'width',
        type: 'integer?',
        doc: 'Breaks lines at spaces so none is wider than this, in pixels.',
      },
      {
        name: 'shadow',
        type: 'string?',
        doc: 'Draws a shadow a pixel down and right of every pixel, in this colour.',
      },
      {
        name: 'line_height',
        type: 'integer?',
        doc: "Pixels from one line's top to the next's (default the font's).",
      },
    ],
  },
  {
    name: 'ScreenTextSize',
    doc: 'How big text is drawn, in pixels.',
    methods: false,
    functions: [],
    fields: [
      { name: 'width', type: 'integer', doc: 'Its widest line.' },
      { name: 'height', type: 'integer', doc: 'All its lines.' },
    ],
  },
  {
    name: 'ScreenImageOptions',
    doc: 'How a picture is drawn on a screen. Every key is optional.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'width',
        type: 'integer?',
        doc: 'Drawn this wide, in pixels (default its own width, or kept in shape with `height`).',
      },
      {
        name: 'height',
        type: 'integer?',
        doc: 'Drawn this tall, in pixels (default its own height, or kept in shape with `width`).',
      },
      {
        name: 'dither',
        type: '("none"|"floyd_steinberg"|"ordered")?',
        doc: 'How its colours become map colours: `"floyd_steinberg"` (the default) spreads each pixel\'s error to its neighbours, best for photos; `"ordered"` uses a fixed pattern; `"none"` takes each pixel\'s nearest.',
      },
    ],
  },
  {
    name: 'VideoPlayOptions',
    doc: 'How `screen:play` plays a video. Every key is optional.',
    methods: false,
    functions: [],
    fields: [
      { name: 'loop', type: 'boolean?', doc: 'Starts it again when it ends (default false).' },
      {
        name: 'speed',
        type: 'number?',
        doc: 'How fast: 0.25 to 4 (default 1). Its sound plays only at 1.',
      },
      { name: 'start', type: 'number?', doc: 'Where to start, in seconds (default 0).' },
      {
        name: 'volume',
        type: 'number?',
        doc: "Its sound's volume, 0 to 1 (default 1); 0 plays it silent.",
      },
      {
        name: 'x',
        type: 'integer?',
        doc: 'Where its left is on the screen, in pixels (default 0).',
      },
      {
        name: 'y',
        type: 'integer?',
        doc: 'Where its top is on the screen, in pixels (default 0).',
      },
      {
        name: 'audio_range',
        type: 'number?',
        doc: "How near where the screen hangs a player must be to hear it, in blocks (default 32). A screen that hangs nowhere plays its sound to everyone it's drawn for.",
      },
    ],
  },
  {
    name: 'ScreenPoint',
    doc: 'A pixel of a screen a player aims at.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'screen',
        type: 'Screen',
        doc: "The screen: the player's own view of it, when they have one.",
      },
      { name: 'x', type: 'integer', doc: 'From its left edge.' },
      { name: 'y', type: 'integer', doc: 'From its top edge.' },
      { name: 'distance', type: 'number', doc: "From the player's eyes, in blocks." },
    ],
  },
  {
    name: 'ScreenClickEvent',
    doc: 'A player clicked a screen.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      {
        name: 'screen',
        type: 'Screen',
        doc: "The screen clicked: the player's own view of it, when they have one.",
      },
      { name: 'player', type: 'Player', doc: 'Who clicked.' },
      { name: 'x', type: 'integer', doc: 'The pixel aimed at, from the left edge.' },
      { name: 'y', type: 'integer', doc: 'From the top edge.' },
      { name: 'click', type: '"left"|"right"', doc: 'Which button.' },
    ],
  },
  {
    name: 'ScreenHoverEvent',
    doc: 'The pixel a player aims at on a screen moved.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      {
        name: 'screen',
        type: 'Screen',
        doc: "The screen: the player's own view of it, when they have one.",
      },
      { name: 'player', type: 'Player', doc: 'Who.' },
      { name: 'x', type: 'integer', doc: 'The pixel aimed at now, from the left edge.' },
      { name: 'y', type: 'integer', doc: 'From the top edge.' },
    ],
  },
  {
    name: 'ScreenLeaveEvent',
    doc: 'A player stopped aiming at a screen.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'screen', type: 'Screen', doc: 'The screen.' },
      { name: 'player', type: 'Player', doc: 'Who.' },
    ],
  },
  {
    name: 'PlaybackEndEvent',
    doc: 'A video stopped playing.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'playback', type: 'Playback', doc: 'The playback.' },
      {
        name: 'reason',
        type: '"finished"|"stopped"|"replaced"|"removed"|"unloaded"',
        doc: "Why: it played to its end (and doesn't loop); `stop()` was called; another video was played on the screen; the screen went; or the script that played it unloaded.",
      },
    ],
  },
  {
    name: 'PlaybackFrameEvent',
    doc: 'A frame of a video was drawn.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'playback', type: 'Playback', doc: 'The playback.' },
      { name: 'frame', type: 'integer', doc: 'Which frame, 0 first.' },
      { name: 'time', type: 'number', doc: 'Its time in the video, in seconds.' },
    ],
  },
]
