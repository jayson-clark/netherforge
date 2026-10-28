-- The altar's rules: what an item set in the altar's ring (menus/altar) becomes, and what infusing
-- it costs in glow shards. The menu's script only shows and clicks; everything it decides is here,
-- in a library module (it has no event handlers of its own), so tests can infuse without a window.

local altar = {}

local SHARD = { item = "shard" }

---@class AltarRule
---@field name string
---@field label string What the infuse button says it will do.
---@field shards integer Glow shards it takes from the player.
---@field matches fun(item: Item): boolean Whether it takes this item in the ring.
---@field result fun(item: Item): Item What the ring holds afterwards.

---@type AltarRule[]
altar.RULES = {
  {
    name = "kindle",
    label = "Kindle the lantern",
    shards = 3,
    matches = function(item)
      return nf.items.id(item) == "lantern" and not (item.data and item.data.kindled)
    end,
    result = function()
      -- Same project item, different stack: the data rides on the stack wherever it goes.
      return nf.items.create("lantern", {
        name = "<light_purple>Kindled lantern",
        lore = { "<gray>It burns without fuel." },
        glint = true,
        data = { kindled = true },
      })
    end,
  },
  {
    name = "circlet",
    label = "Set glow into the circlet",
    shards = 4,
    matches = function(item)
      return item.kind == "minecraft:golden_helmet" and nf.items.id(item) == nil
    end,
    result = function()
      return nf.items.create("circlet")
    end,
  },
  {
    name = "candy",
    label = "Bottle starlight as candy",
    shards = 2,
    matches = function(item)
      return item.kind == "minecraft:glass_bottle"
    end,
    result = function()
      return nf.items.create("candy")
    end,
  },
}

--- The rule that takes an item, or `nil` when the altar does nothing with it.
---@param item Item?
---@return AltarRule?
function altar.rule_for(item)
  if not item then
    return nil
  end
  for _, rule in ipairs(altar.RULES) do
    if rule.matches(item) then
      return rule
    end
  end
  return nil
end

--- The infuse button's lore for what's in the ring.
---@param item Item?
---@return string[]
function altar.describe(item)
  if not item then
    return { "<gray>Set something in the ring:", "<gray>a lantern, a golden helmet, a bottle" }
  end
  local rule = altar.rule_for(item)
  if not rule then
    return { "<red>The altar doesn't answer to that" }
  end
  return {
    "<light_purple>" .. rule.label,
    ("<gray>Costs <glyph:ui/spark> <white>%d</white> glow shards"):format(rule.shards),
  }
end

--- Infuses one of the item in the ring, taking the cost from the player's inventory. Gives
--- `result, rest` (the infused item, and what's left of a stack that was in the ring, or `nil`),
--- or `nil, why` when it can't.
---@param player Player
---@param item Item
---@return Item?
---@return Item|string|nil
function altar.infuse(player, item)
  local rule = altar.rule_for(item)
  if not rule then
    return nil, "The altar doesn't answer to that"
  end
  local inventory = assert(player:inventory(), "an offline player has no inventory")
  if not inventory:has_item(SHARD, rule.shards) then
    return nil, ("The altar asks for %d glow shards"):format(rule.shards)
  end
  inventory:remove_item(SHARD, rule.shards)
  local rest = nil
  if (item.count or 1) > 1 then
    rest = item
    rest.count = item.count - 1
  end
  nf.emit("altar:infused", { player = player, rule = rule.name })
  return rule.result(item), rest
end

return altar
