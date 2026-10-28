local funnel = nf.menus.shared("funnel")
log("hopper", funnel:size(), funnel:rows())
funnel:set_item(4, {
  kind = "minecraft:paper",
  max_stack_size = 16,
  rarity = "epic",
  attribute_modifiers = {
    {
      attribute = "minecraft:attack_damage",
      amount = 6,
      operation = "add_value",
      slot = "main_hand",
    },
    {
      id = "it:speed",
      attribute = "minecraft:movement_speed",
      amount = 0.1,
      operation = "add_multiplied_total",
    },
  },
  can_break = { "minecraft:stone" },
  can_place_on = { "minecraft:grass_block" },
  food = { nutrition = 4, saturation = 2.5, can_always_eat = true, eat_seconds = 0.8 },
  cooldown = { seconds = 2, group = "it:pearls" },
})
local back = funnel:item(4)
local m = back.attribute_modifiers
log(
  "components",
  back.max_stack_size,
  back.rarity,
  #m,
  m[1].attribute,
  m[1].slot,
  tostring(m[1].id),
  m[2].id,
  m[2].operation
)
log("adventure", back.can_break[1], back.can_place_on[1])
log(
  "food",
  back.food.nutrition,
  back.food.saturation,
  tostring(back.food.can_always_eat),
  back.food.eat_seconds
)
log("cooldown", back.cooldown.seconds, back.cooldown.group, tostring(back.raw))
funnel:set_item(3, { kind = "minecraft:stick", hold = true })
funnel:set_item(
  2,
  { kind = "minecraft:bread", use_effects = { speed_multiplier = 1, can_sprint = true } }
)
local held, bread = funnel:item(3), funnel:item(2)
log("hold", tostring(held.hold), tostring(held.use_effects), tostring(held.raw))
log(
  "use effects",
  bread.use_effects.speed_multiplier,
  tostring(bread.use_effects.can_sprint),
  tostring(bread.use_effects.interact_vibrations),
  tostring(bread.raw)
)
local ok, message =
  pcall(funnel.set_item, funnel, 0, { kind = "minecraft:diamond_sword", max_stack_size = 2 })
log("stackable sword", tostring(ok), tostring(message:find("durability") ~= nil))
