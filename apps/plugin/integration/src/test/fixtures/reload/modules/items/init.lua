local bank = nf.menus.shared("bank")
bank:set_item(0, {
  kind = "minecraft:gold_nugget",
  name = "<gold>Coin",
  data = { coin = true, worth = 3, tags = { "a", "b" }, minted = vec3(1, 2.5, 3), ["$odd"] = 1.0 },
})
local back = bank:item(0)
log(
  "item data",
  back.kind,
  tostring(back.data.coin),
  back.data.worth,
  back.data.tags[2],
  tostring(back.raw)
)
log(
  "typed item data",
  tostring(back.data.minted),
  back.data.minted == vec3(1, 2.5, 3),
  math.type(back.data["$odd"])
)
bank:set_item(1, { kind = "minecraft:gold_nugget" })
log("no item data", tostring(bank:item(1).data))

local saved = nf.data("integration")
saved.starts = (saved.starts or 0) + 1
saved.spot = saved.spot or vec3(4, 5, 6)
log("saved data", saved.starts, tostring(saved.spot))
