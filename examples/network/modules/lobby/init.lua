-- The lobby: where players join the network. Portals to the arena and the market, and the
-- network's board: who's on each server, the arena's champions and the market's prices, all read
-- from the maps every server shares.
--
-- Its bots stroll about, chat, and now and then head off to the arena or the market.

local net = require("net")
local plaza = require("plaza")
local crowd = require("crowd")
local boards = require("boards")

if net.role() ~= "lobby" then
  return {}
end

local lobby = {}

local CHATTER = {
  "anyone up for a fight in the arena?",
  "diamonds are cheap at the market right now",
  "gg everyone",
  "how do I get to the market?",
  "this lobby is so shiny",
  "who's winning in the arena?",
  "psst: /daily is free coins",
  "brb",
  "lol",
  "nice weather today",
}

crowd.set_brain(function(member, random)
  local bot = member.bot
  local roll = random:number(0, 1)
  if roll < 0.04 and crowd.travel(member, random:pick({ "arena", "market" })) then
    return
  end
  bot:walk_to(plaza.random_spot(random, 2, 11))
  if roll > 0.7 then
    crowd.say(member, random:pick(CHATTER))
  elseif roll > 0.6 then
    bot:jump()
  end
  nf.wait(random:integer(40, 140))
end)

local board ---@type Board?

local function refresh()
  if board then
    board:set(boards.join(boards.network_lines(), boards.wins_lines(5), boards.price_lines()))
  end
end

nf.after(20, function()
  plaza.build()
  -- A pedestal for the board, in front of the spawn.
  plaza.world:fill_blocks(
    vec3(-1, plaza.Y, -8),
    vec3(1, plaza.Y, -8),
    "minecraft:polished_deepslate"
  )
  board = plaza.board(vec3(0.5, plaza.Y + 1.2, -7.5), 1.3)
  refresh()
  crowd.start()
end)

nf.every(20, refresh)

return lobby
