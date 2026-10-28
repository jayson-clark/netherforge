local this = this --[[@as Screen]]

-- The lobby's board: a title, the time players have clicked it, and a ball that bounces when they do.
local clicks = 0

local function draw()
  this:clear("#101018")
  this:rectangle(
    4,
    4,
    this:width() - 8,
    this:height() - 8,
    { outline = "#ffcc00", thickness = 2, radius = 8 }
  )
  this:text(
    this:width() / 2,
    14,
    "<gold>Lobby</gold>",
    { align = "center", scale = 3, shadow = "#000000" }
  )
  this:image(16, 16, "ui/glyph/coin", { width = 32, dither = "none" })
  this:text(this:width() / 2, 60, "Clicked " .. clicks .. " times", { align = "center", scale = 2 })
  this:text(
    this:width() / 2,
    90,
    "0123456789",
    { font = "pixel", align = "center", scale = 2, color = "#88ccff" }
  )
end

draw()

this:on("click", function(event)
  clicks = clicks + 1
  draw()
  this:circle(event.x, event.y, 4, { fill = "#ff4040" })
  -- The bouncing ball plays in the bottom right corner, over what's drawn there.
  this:play("bounce", { x = this:width() - 136, y = this:height() - 136 })
end)
