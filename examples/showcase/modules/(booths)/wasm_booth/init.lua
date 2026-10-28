-- Booth: WebAssembly. Heavy computation handed off the main thread: wasm/mandelbrot/module.wasm
-- works out the Mandelbrot set, and this script paints what it answers onto a screen.
local expo = require("expo")

local W, H = 256, 256
-- Escape-time bands, from the set itself (black) out through purple to gold.
local PALETTE = {
  "#000000",
  "#1a0b3d",
  "#2e1065",
  "#4c1d95",
  "#6d28d9",
  "#8b5cf6",
  "#c084fc",
  "#f0abfc",
  "#fcd34d",
  "#fde68a",
}

expo.booth({
  id = "wasm",
  title = "WebAssembly",
  summary = "Heavy computation off the main thread, in a module",
  icon = "minecraft:redstone",
  lines = {
    '<gold>WebAssembly</gold>: wasm/<id>/module.wasm, behind "requires": { "wasm": true }.',
    "",
    "<gray>Lua runs on the main thread under an instruction budget; a module runs",
    "<gray>off it, at native-ish speed, and can only answer the script that asked:",
    "<gray>no world, no network, no files but its own.",
    "",
    "<gray>Write it in Rust, C, C++ or anything that compiles to WASI preview 1.",
    "<gray>This one was hand-written in WebAssembly text: render(w, h, zoom) returns",
    "<gray>one character per pixel, which the script paints onto the screen.",
  },
  build = function(booth)
    local screen = nf.screens.create({ width = 2, height = 2, background = "#000000" })
    booth:hang(screen, 4)
    local zoom = 1

    local function render()
      screen:text(W // 2, H // 2 - 8, "<gray>computing…", { align = "center" })
      nf.task(function()
        local instance, err = nf.wasm.instantiate("mandelbrot")
        if not instance then
          screen:text(W // 2, H // 2 + 8, "<red>" .. tostring(err), { align = "center" })
          return
        end
        local text, failed = instance:call_text("render", { W, H, zoom })
        instance:close()
        if not text then
          screen:text(W // 2, H // 2 + 8, "<red>" .. tostring(failed), { align = "center" })
          return
        end
        -- A row at a time, sixteen rows a tick: painting the whole picture at once would take
        -- more instructions than a script may run in one go.
        for y = 0, H - 1 do
          local row = {}
          for x = 1, W do
            row[x] = PALETTE[text:byte(y * W + x) - 47] or "#000000"
          end
          screen:set_pixels(0, y, W, row)
          if y % 16 == 15 then
            nf.wait(1)
          end
        end
        screen:text(4, H - 12, ("<white>zoom %d×"):format(zoom), { scale = 1 })
      end)
    end
    render()

    booth:kiosk(3, 4, "<yellow>Zoom in", function()
      zoom = math.min(zoom * 2, 256)
      render()
    end)
    booth:kiosk(3, 8, "<yellow>Reset", function()
      zoom = 1
      render()
    end)
    booth:caption(
      3,
      6,
      "<white>Mandelbrot, from a module\n<gray>65,536 pixels worked out off the main thread each time.",
      3.2
    )
  end,
})
