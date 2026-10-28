-- The library's public module. A project that depends on the library uses it
-- with require("library:greetings"): netherforge.json exports it. The phrases
-- it picks from are the library's own (not exported), so only the library's
-- scripts can require them, and they do it by their bare name.
local phrases = require("phrases")

local greetings = {}

--- A greeting for a player, in the library's words.
---@param name string
---@return string
function greetings.hello(name)
  return "<aqua>" .. phrases.opening .. ", " .. name .. "!"
end

return greetings
