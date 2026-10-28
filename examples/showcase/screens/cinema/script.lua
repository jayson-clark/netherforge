local this = this --[[@as Screen]]

-- The big screen: a poster until the controls play the video on it.

local function poster()
  this:clear("#08080c")
  this:rectangle(
    16,
    16,
    this:width() - 32,
    this:height() - 32,
    { outline = "#ffcc00", thickness = 3, radius = 12 }
  )
  this:text(
    this:width() / 2,
    120,
    "<gold>NOW SHOWING",
    { align = "center", scale = 4, shadow = "#000000" }
  )
  this:text(this:width() / 2, 190, "<white>Press <green>Play</green> on the controls", {
    align = "center",
    scale = 2,
  })
  this:text(this:width() / 2, 230, "beside it", { align = "center", scale = 2, color = "#aaaaaa" })
end

poster()

-- Clicking the screen itself shows where you clicked, until the next frame draws over it.
this:on("click", function(event)
  this:circle(event.x, event.y, 6, { fill = "#ff4040", outline = "#ffffff" })
end)

-- When the video stops, the poster comes back.
nf.on("cinema:stopped", function()
  poster()
end)
