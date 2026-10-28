-- The library's exported loot table (examples/library/loot/gems.json, which names the library's gem bare),
-- rolled from the project and filled into a real chest: its stacks are the library's gem.
nf.commands.register("it-loot", function()
  local seed, items = 0, {}
  repeat
    seed = seed + 1
    items = nf.loot.roll("library:gems", { seed = seed })
  until #items > 0
  local block = assert(nf.worlds.default():block(vec3(214, -62, 214)))
  block:set_state("minecraft:chest")
  local chest = assert(block:inventory())
  local left = nf.loot.fill("library:gems", chest, { seed = seed })
  local found = {}
  for _, item in pairs(chest:items()) do
    found[#found + 1] = item.item
  end
  local _, problem = pcall(nf.loot.roll, "library:nope")
  log(
    "loot",
    items[1].item,
    left,
    #found == #items,
    found[1],
    tostring(problem):find('there\'s no loot table "library:nope"', 1, true) ~= nil
  )
  block:set_state("minecraft:air")
end)
