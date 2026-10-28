# Videos

A video is a film [screens](screen.md) play: `videos/<id>/video.json`, the
video file it's made from beside it, and what the editor converts it into.
The server can't decode a video file, so the editor converts it once (with
ffmpeg) into the frames in map colours, at the size and rate the file asks
for, and its sound into the resource pack. Screens play the converted frames.

```json
{
  "$schema": "../../.netherforge/schema/video.schema.json",
  "source": "trailer.mp4",
  "width": 4,
  "height": 3,
  "fps": 20,
  "dither": "floyd_steinberg",
  "fit": "contain",
  "start": 2.5,
  "end": 30,
  "audio": true
}
```

| Key      | Meaning                                                                                                                                                                                             | Default           |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| `source` | The video it's converted from: a file in its folder that ffmpeg reads (mp4, webm, mov, gif, …).                                                                                                     | required          |
| `width`  | Maps across the frames fill, 1 to 16.                                                                                                                                                               | required          |
| `height` | Maps down, 1 to 16.                                                                                                                                                                                 | required          |
| `fps`    | Frames a second, 1 to 20: a server ticks 20 times a second.                                                                                                                                         | 20                |
| `dither` | How colours become map colours: `floyd_steinberg` (spreads each pixel's error, best for film), `ordered` (a fixed pattern that flickers less from frame to frame) or `none` (each pixel's nearest). | `floyd_steinberg` |
| `fit`    | How a picture of another shape fills the maps: `contain` (all of it, black bars), `cover` (all the maps, cut) or `stretch`.                                                                         | `contain`         |
| `start`  | Where in the source to start, in seconds.                                                                                                                                                           | 0                 |
| `end`    | Where to stop, in seconds.                                                                                                                                                                          | its end           |
| `audio`  | Whether its sound is kept.                                                                                                                                                                          | `true`            |

A size outside 1 to 16 maps is `screen.size`; `fps` outside 1 to 20 is
`video.fps`; a `start` below 0, or an `end` before it, is `video.range`; a
`source` that isn't a file name in the folder (or names a converted file) is
`video.source`.

## Converting

**Convert** in the video's editor runs ffmpeg (Settings → Server says where
it is; empty finds it on `PATH`), then writes, beside `video.json`:

- `video.nfv`: the frames, in the map colours of the project's Minecraft
  version (from the dev server's game data, so start it once first);
- `audio.ogg`: its sound, the resource pack's sound event `video.<id>`;
- `audio/0.ogg`, `audio/1.ogg`, …: the sound again in five-second pieces,
  `video.<id>.<n>`. The game can only start a sound from its beginning, so a
  video that was paused or moved through picks its sound up at the next piece.

Commit them with the project: the server reads them as they are, and the
source needn't be there (leave it out of git if it's big). A video whose
source is there but nothing converted is `video.not-converted`, and one whose
sound is kept but has no `audio.ogg` is `video.audio-missing` (both warnings).
When the converted frames were made for other settings than the file has now
(another size, rate, dithering, fit or sound), the server says
`video.stale`: convert it again. The editor also says when the source changed
since.

## The frames' file

`video.nfv` is binary, big-endian: a 72-byte header, an index, then the
frames.

| Bytes | Holds                                                                |
| ----- | -------------------------------------------------------------------- |
| 0–3   | `NFV1`                                                               |
| 4–5   | the layout's version, 1                                              |
| 6–9   | its maps across, then down                                           |
| 10–11 | frames a second                                                      |
| 12–15 | how many frames                                                      |
| 16–17 | frames from one key frame to the next                                |
| 18    | the dithering (0 none, 1 Floyd–Steinberg, 2 ordered)                 |
| 19    | the fit (0 contain, 1 cover, 2 stretch)                              |
| 20–27 | where in the source it starts and ends, in milliseconds (0: its end) |
| 28    | 1 when it has a sound                                                |
| 32–63 | the source's SHA-256                                                 |
| 64–67 | which map colours it was made for (FNV-1a of the palette)            |

Then a frame's offset in the file for each frame (8 bytes each), then each
frame: a byte (0 a key frame, 1 the changes since the frame before), its
length (4 bytes), and that many bytes of zlib-deflated data. A key frame is
every pixel's map colour, row by row across the whole picture; a change is
rectangles, each `x`, `y`, `width`, `height` (2 bytes each, in pixels) and its
pixels row by row, one round each map's changes.

## Playing one

```lua
local tv = nf.screens.get("lobby_tv")
local show = tv:play("trailer", { loop = true, volume = 0.8 })
show:on("end", function(event) log("ended:", event.reason) end)
```

`Playback` pauses, resumes, seeks, changes speed and volume, and loops; its
sound plays to the players the screen is drawn for, near where it hangs.
