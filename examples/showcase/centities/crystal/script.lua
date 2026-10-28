local this = this --[[@as Centity]]

-- One display entity draws the whole crystal, and set_model swaps the look it
-- shows: a flipbook of two frames, every ten ticks, while `spin` turns it. The
-- spin has a key every 90°: rotation goes the short way between keys, so one
-- from 0° straight to 360° wouldn't turn at all.
local gem = assert(this:node("gem"))
local frames = { "ui/crystal", "ui/crystal_lit" }
local frame = 1

nf.every(10, function()
  frame = frame % #frames + 1
  gem:set_model(frames[frame])
end)

-- A click spins it four times as fast for two seconds, from wherever it had got to.
local slow_down = nil

gem:on("click", function(event)
  this:set_animation_speed("spin", 4)
  if slow_down then
    slow_down:cancel()
  end
  slow_down = nf.after(40, function()
    this:set_animation_speed("spin", 1)
  end)
  event.player:send_actionbar(
    "<red>The crystal hums. It's showing " .. (gem:model() or "nothing") .. "."
  )
end)
