local this = this --[[@as Screen]]

-- The cinema's controls: buttons that play, pause and restart the video on the big screen, light up under the
-- cursor, and a line that says how far it has got.

local cinema = nf.screens.get("cinema")

---@type Playback?
local show = nil
local hovered = nil ---@type string?

local buttons = {
  { key = "play", label = "Play", x = 16, y = 56 },
  { key = "pause", label = "Pause", x = 136, y = 56 },
  { key = "restart", label = "Restart", x = 16, y = 128 },
  { key = "loop", label = "Loop", x = 136, y = 128 },
}
local WIDTH, HEIGHT = 104, 56

local function playing()
  return show ~= nil and show:exists()
end

local function label(button)
  if button.key == "pause" and playing() and show and show:is_paused() then
    return "Resume"
  end
  if button.key == "loop" then
    return (playing() and show and show:is_looping()) and "Loop: on" or "Loop: off"
  end
  return button.label
end

local function status()
  this:fill(16, 200, this:width() - 32, 40, "#14141e")
  local text = "<gray>Stopped"
  if playing() and show then
    local state = show:is_paused() and "<yellow>Paused" or "<green>Playing"
    text = string.format("%s <white>%.1f / %.1f s", state, show:time() or 0, show:length())
  end
  this:text(this:width() / 2, 210, text, { align = "center", scale = 2 })
end

local function draw()
  this:clear("#14141e")
  this:text(this:width() / 2, 14, "<gold>Cinema", { align = "center", scale = 3 })
  for _, button in ipairs(buttons) do
    local lit = hovered == button.key
    this:rectangle(button.x, button.y, WIDTH, HEIGHT, {
      fill = lit and "#3a3a6a" or "#26263c",
      outline = lit and "#ffcc00" or "#6a6a9a",
      thickness = 2,
      radius = 6,
    })
    this:text(button.x + WIDTH / 2, button.y + 20, label(button), { align = "center", scale = 2 })
  end
  status()
end

local function at(x, y)
  for _, button in ipairs(buttons) do
    if x >= button.x and x < button.x + WIDTH and y >= button.y and y < button.y + HEIGHT then
      return button.key
    end
  end
  return nil
end

local function play()
  show = cinema:play("intro")
  if show then
    show:on("end", function(event)
      -- Replaced by a restart: the new one carries on. Otherwise the poster comes back.
      if event.reason ~= "replaced" then
        nf.emit("cinema:stopped")
        draw()
      end
    end)
  end
end

this:on("click", function(event)
  local key = at(event.x, event.y)
  if key == "play" and not playing() then
    play()
  elseif key == "pause" and playing() and show then
    if show:is_paused() then
      show:resume()
    else
      show:pause()
    end
  elseif key == "restart" then
    play()
  elseif key == "loop" and playing() and show then
    show:set_looping(not show:is_looping())
  elseif key == nil then
    return
  end
  event.player:play_sound("minecraft:ui.button.click")
  draw()
end)

this:on("hover", function(event)
  local key = at(event.x, event.y)
  if key ~= hovered then
    hovered = key
    draw()
  end
end)

this:on("leave", function()
  hovered = nil
  draw()
end)

-- How far it has got, twice a second while it plays.
nf.every(10, function()
  if playing() then
    status()
  end
end)

draw()
