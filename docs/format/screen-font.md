# Screen fonts

A screen font is a font [screens](screen.md) write text in. The server draws
it into maps' pixels, so players never download it. The game's own map font
is always there as `default`; a project adds its own as
`screen_fonts/<id>/font.json`, of one of three types:

- `pixels`: its characters drawn in the file itself, a row of `#` (a pixel) and
  `.` (none) at a time. A pixel-art font, with nothing beside it.
- `bitmap`: a picture beside it of its characters in a grid, as the game's own
  bitmap fonts are drawn.
- `truetype`: a TrueType or OpenType file beside it, drawn at a size.

```json
{
  "$schema": "../../.netherforge/schema/screen_font.schema.json",
  "type": "pixels",
  "glyphs": {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"]
  },
  "spacing": 1,
  "spaceWidth": 2,
  "lineHeight": 7
}
```

```json kind=screen_font
{ "type": "bitmap", "file": "letters.png", "chars": ["ABCD", "EFGH"] }
```

```json kind=screen_font
{ "type": "truetype", "file": "font.ttf", "size": 16, "antialias": false }
```

| Key          | Meaning                                                                                                           | Default                 |
| ------------ | ----------------------------------------------------------------------------------------------------------------- | ----------------------- |
| `type`       | `pixels`, `bitmap` or `truetype`.                                                                                 | required                |
| `glyphs`     | A pixels font's characters: each one character long, as rows of `#` and `.`, each row as wide, every one as tall. | required for `pixels`   |
| `file`       | A bitmap font's picture (`.png`), or a TrueType font's file (`.ttf`, `.otf`), in the font's folder.               | required for the others |
| `chars`      | A bitmap font's characters, a string per row of its grid, every row as long; `\u0000` leaves a cell empty.        | required for `bitmap`   |
| `size`       | A TrueType font's size, in pixels: 4 to 128.                                                                      | 16                      |
| `antialias`  | Whether a TrueType font's edges blend into what's behind them, rather than crisp pixels.                          | `false`                 |
| `spacing`    | Pixels between one character and the next: 0 to 16.                                                               | 1                       |
| `spaceWidth` | How wide a space is, in pixels.                                                                                   | a third of its height   |
| `lineHeight` | Pixels from one line's top to the next's.                                                                         | its height and 1        |

A bitmap font's picture is cut into its grid (so its width is a whole number
of `chars`' columns and its height of its rows, `screen_font.grid`), and each
character is as wide as its opaque columns, as the game measures its own.

A font called `default` is `screen_font.id`; a `file` of the wrong kind is
`screen_font.file`, and one that isn't in the folder `screen_font.missing`; a field of another type is `screen_font.field`; `chars`
missing or rows of different lengths is `screen_font.chars`; a glyph that
isn't one character, or rows that aren't all `#` and `.` and as wide, or a
glyph taller or shorter than the rest, is `screen_font.glyph`; and a size,
spacing or line height out of bounds is `screen_font.size`.

Scripts write in one by its id:

```lua
screen:text(4, 4, "0123", { font = "pixel", scale = 2 })
```
