-- What the plazas' boards say, worked out from the network's shared maps: every server reads its
-- own copy of them, so a win in the arena shows on the lobby's board a moment later.

local net = require("net")
local presence = require("presence")
local economy = require("economy")

local boards = {}

--- The servers and how many are on each.
---@return string[]
function boards.network_lines()
  local lines = { "<white><b>The network</b>" }
  local network = presence.network()
  for _, entry in ipairs(network) do
    lines[#lines + 1] = ("%s <white>%d online</white> <dark_gray>(%d bots)"):format(
      net.TITLES[entry.role],
      #entry.players + #entry.bots,
      #entry.bots
    )
  end
  if #network == 0 then
    lines[#lines + 1] = "<gray>connecting…"
  end
  return lines
end

--- The arena's best: the `n` with the most wins, most first.
---@param n integer
---@return { name: string, wins: integer }[]
function boards.top_wins(n)
  local list = {}
  for name, wins in pairs(net.wins:all()) do
    list[#list + 1] = { name = name, wins = math.floor(wins) }
  end
  table.sort(list, function(a, b)
    if a.wins ~= b.wins then
      return a.wins > b.wins
    end
    return a.name < b.name
  end)
  while #list > n do
    table.remove(list)
  end
  return list
end

---@param n integer
---@return string[]
function boards.wins_lines(n)
  local lines = { "<red><b>Arena champions</b>" }
  for i, entry in ipairs(boards.top_wins(n)) do
    lines[#lines + 1] = ("<gray>%d.</gray> <white>%s</white> <yellow>%d</yellow>"):format(
      i,
      net.plain(entry.name),
      entry.wins
    )
  end
  if #lines == 1 then
    lines[#lines + 1] = "<gray>no fights yet"
  end
  return lines
end

local ARROW = { up = "<green>▲", down = "<red>▼", flat = "<gray>–" }

---@return string[]
function boards.price_lines()
  local lines = { "<gold><b>Market prices</b>" }
  local any = false
  for _, item in ipairs(economy.NAMES) do
    local quote = net.prices:get(item)
    if quote then
      any = true
      lines[#lines + 1] = ("<white>%s</white> <yellow>%d</yellow> %s <dark_gray>(%d left)"):format(
        (item:gsub("_", " ")),
        quote.price,
        ARROW[quote.trend] or ARROW.flat,
        quote.stock
      )
    end
  end
  if not any then
    lines[#lines + 1] = "<gray>the market is closed"
  end
  return lines
end

--- Lines joined for a board, with a gap between groups.
---@param ... string[]
---@return string
function boards.join(...)
  local parts = {}
  for _, lines in ipairs({ ... }) do
    parts[#parts + 1] = table.concat(lines, "\n")
  end
  return table.concat(parts, "\n\n")
end

return boards
