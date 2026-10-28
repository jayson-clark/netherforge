-- The arena: bots spar in pairs inside the ring, and anyone can /duel the next free bot. A win is a
-- point in the network's `wins` map and 10 coins (25 for beating a bot yourself), paid through the
-- hub, and every server hears who won.
--
-- A fight is two fighters (bots or a player and a bot) and one clock: each bot chases its opponent
-- and swings when it's in reach, until someone dies or the time runs out.

local net = require("net")
local plaza = require("plaza")
local crowd = require("crowd")
local boards = require("boards")
local economy = require("economy")

local arena = {}

--- What a fighter wears: a sword and leather in their side's colour.
---@param who Player
---@param colour string `#rrggbb`
function arena.gear(who, colour)
  who:set_equipment("main_hand", { kind = "minecraft:iron_sword", unbreakable = true })
  for slot, kind in pairs({
    head = "leather_helmet",
    chest = "leather_chestplate",
    legs = "leather_leggings",
    feet = "leather_boots",
  }) do
    who:set_equipment(slot, { kind = "minecraft:" .. kind, color = colour, unbreakable = true })
  end
end

--- What a win pays.
arena.PRIZE = { bot = 10, player = 25 }

--- Records `winner`'s win over `loser`, for the whole network: a point, coins and the news.
---@param winner string
---@param loser string
---@param by_player boolean Whether the winner is a player (who earns more).
function arena.record(winner, loser, by_player)
  net.wins:add(winner, 1, nil, function() end)
  economy.pay(winner, by_player and arena.PRIZE.player or arena.PRIZE.bot, function() end)
  net.announce(
    ("<white>%s</white> beat <white>%s</white> in the arena"):format(
      net.plain(winner),
      net.plain(loser)
    )
  )
end

if net.role() ~= "arena" then
  return arena
end

local RED, BLUE = "#c03030", "#3050c0"
local ROUND_TICKS = 20 * 40

--- The fight on now, if any: its two fighters (each a Player, a bot or not) and when it ends.
---@type { a: Player, b: Player, ends: integer, over: boolean }?
local fight = nil
--- Players waiting for a duel, first first.
local queue = {} ---@type Player[]

local function heal(who)
  if who:exists() then
    who:set_health(who:max_health())
    who:set_food(20)
  end
end

-- A bot fighting: close in, swing when in reach, until the fight is over.
local function brawl(member, opponent)
  local bot = member.bot
  while fight and not fight.over and bot:exists() and opponent:exists() do
    local distance = bot:position():distance(opponent:position())
    if distance > 2.6 then
      bot:walk_to(opponent:position(), { sprint = true })
    else
      bot:attack(opponent)
      nf.wait(12)
    end
    nf.wait(1)
  end
end

local function finish(winner, loser)
  if not fight or fight.over then
    return
  end
  fight.over = true
  if winner then
    arena.record(winner:name(), loser:name(), not crowd.is_bot(winner))
  else
    net.announce("A fight in the arena ran out of time: a draw")
  end
  for _, who in ipairs({ fight.a, fight.b }) do
    local member = crowd.members[who:name()]
    if member then
      nf.after(40, function()
        if member.bot:exists() then
          member.bot:respawn()
          heal(member.bot)
          member.busy = false
        end
      end)
    end
  end
  nf.after(60, function()
    fight = nil
  end)
end

local function start(a, b)
  fight = { a = a, b = b, ends = nf.server.tick() + ROUND_TICKS, over = false }
  for i, who in ipairs({ a, b }) do
    heal(who)
    arena.gear(who, i == 1 and RED or BLUE)
    local member = crowd.members[who:name()]
    if member then
      member.busy = true
      nf.task(function()
        member.bot:walk_to(vec3(i == 1 and -3.5 or 4.5, plaza.Y, 0.5))
        nf.wait(20)
        brawl(member, i == 1 and b or a)
      end)
    else
      who:send_title("<red>Fight!", "<gray>vs <white>" .. (i == 1 and b or a):name())
    end
  end
  net.announce(
    ("<white>%s</white> and <white>%s</white> are fighting in the arena"):format(a:name(), b:name())
  )
end

-- Who loses a fight is who dies: the other one wins.
nf.on("player_death", function(event)
  local dead = event.player
  event.keep_inventory = true
  event.drops = {}
  if fight and not fight.over then
    local name = dead:name()
    if name == fight.a:name() or name == fight.b:name() then
      event.message = nil
      finish(name == fight.a:name() and fight.b or fight.a, dead)
    end
  end
end)

-- The clock: a fight that runs too long is a draw, and a free arena starts the next one.
nf.every(20, function()
  if fight then
    if not fight.over and nf.server.tick() > fight.ends then
      finish(nil, nil)
    end
    return
  end
  while #queue > 0 and not queue[1]:exists() do
    table.remove(queue, 1)
  end
  local challenger = table.remove(queue, 1)
  local first = crowd.idle()
  if challenger and first then
    start(first.bot, challenger)
  elseif first then
    local second = crowd.idle(first.name)
    if second then
      start(first.bot, second.bot)
    end
  end
end)

nf.commands.register("duel", {
  description = "Fight the next free bot in the arena",
  players_only = true,
}, function(event)
  local player = assert(event.player)
  for _, waiting in ipairs(queue) do
    if waiting:name() == player:name() then
      player:send_message("<gray>You're already waiting for a fight.")
      return
    end
  end
  queue[#queue + 1] = player
  player:send_message("<red>You're in!</red> <gray>Your fight starts as soon as the arena is free.")
end)

-- Between fights, bots warm up: stroll the ring and chat.
local CHATTER = { "who's next?", "I'm on a streak", "ow", "rematch!", "gg", "come at me" }
crowd.set_brain(function(member, random)
  if random:number(0, 1) < 0.03 and crowd.travel(member, random:pick({ "lobby", "market" })) then
    return
  end
  member.bot:walk_to(plaza.random_spot(random, 3, 8))
  if random:number(0, 1) < 0.25 then
    crowd.say(member, random:pick(CHATTER))
  end
  nf.wait(random:integer(30, 90))
end)

local board ---@type Board?

nf.after(20, function()
  plaza.build()
  -- A ring of fence round the fighting floor, open towards both portals.
  for angle = 0, 359, 4 do
    local a = math.rad(angle)
    local x, z = math.floor(math.cos(a) * 9 + 0.5), math.floor(math.sin(a) * 9 + 0.5)
    if math.abs(z) > 1 then
      plaza.world:set_block(vec3(x, plaza.Y, z), "minecraft:dark_oak_fence")
    end
  end
  board = plaza.board(vec3(0.5, plaza.Y + 4, -11.5), 1.3)
  crowd.start()
end)

nf.every(20, function()
  if not board then
    return
  end
  local now = { "<white><b>Now</b>" }
  if fight and not fight.over then
    now[#now + 1] = ("<red>%s</red> <gray>vs</gray> <blue>%s</blue>"):format(
      fight.a:name(),
      fight.b:name()
    )
  else
    now[#now + 1] = "<gray>next fight soon"
  end
  if #queue > 0 then
    now[#now + 1] = ("<gray>%d waiting to /duel"):format(#queue)
  end
  board:set(boards.join(boards.wins_lines(8), now))
end)

-- Everyone here can fight: arriving is gearing up.
nf.on("player_arrive", function(event)
  arena.gear(event.player, BLUE)
  event.player:send_message(
    "<red>Welcome to the arena.</red> <gray>/duel to fight the next free bot."
  )
end)

return arena
