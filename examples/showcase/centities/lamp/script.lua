local this = this --[[@as Centity]]

-- `bob` floats the lamp and turns it, on its own (autoplay, loop). A click
-- plays `squash` over it: an additive clip, so its scale keys multiply what
-- `bob` leaves rather than taking the node over, and the lamp keeps floating
-- while it squashes. The click also swaps the block it shows.
local light = assert(this:node("light"))
local label = assert(this:node("label"))

local looks = {
  { block = "minecraft:sea_lantern", text = "<aqua>Sea lantern" },
  { block = "minecraft:ochre_froglight", text = "<gold>Ochre" },
  { block = "minecraft:verdant_froglight", text = "<green>Verdant" },
  { block = "minecraft:pearlescent_froglight", text = "<light_purple>Pearlescent" },
}
local look = 1

this:on("click", function()
  look = look % #looks + 1
  light:set_display_block(looks[look].block)
  label:set_display_text(looks[look].text)
  this:play_animation("squash")
end)
