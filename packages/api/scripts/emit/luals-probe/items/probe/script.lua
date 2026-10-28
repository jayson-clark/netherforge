local this = this --[[@as ProjectItem]]

this:on("use", function(event)
  print(event.player:name(), event.item.count, event.hand, nf.items.id(event.item))
end)
this:on("hit", function(event)
  event.amount = event.amount * 2
  print(event.entity:kind())
end)
this:on("break_block", function(event)
  print(event.block:kind(), #event.drops)
end)
this:on("pickup", function(event)
  print(event.entity:kind(), event.item.data)
end)

local stack = this:create({ count = 2 })
if stack then
  print(stack.kind, stack.item)
end
local made = nf.items.create(this:id(), { data = { charges = 3 } })
print(made.name, #nf.items.all())
local other = nf.items.get("ruby")
if other then
  other:once("drop", function(event)
    print(event.item.kind)
  end)
end
