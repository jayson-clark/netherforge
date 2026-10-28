-- The crowd: bots that make each server feel lived in. They're real players to their server (on
-- the player list, chatting, walking, fighting), joined by scripts with nf.bots.
--
-- A bot belongs to one server: the proxy never saw it log in, so it can't move it. Travelling is
-- the network's doing instead: the bot walks into a portal and leaves, and the server it was
-- heading for hears about it on the `travel` channel and joins a bot of the same name at its own
-- portal. Every server does both halves, so the crowd drifts around the network.
--
-- What a bot does all day is its "brain": a function each part sets (crowd.set_brain) and runs in
-- a task per bot, with the bot and a few helpers. The lobby's wanders and chats, the arena's
-- fights, the market's trades.

local net = require("net")
local plaza = require("plaza")

local crowd = {}

local random = nf.random.new()

--- Who starts on which part's server. Names are 3 to 16 letters, digits or `_`.
crowd.ROSTERS = {
  lobby = { "Nova", "Pixel", "Mossy", "Juniper" },
  arena = { "Flint", "Rook", "Ember", "Kestrel" },
  market = { "Tansy", "Bramble", "Sable", "Wren" },
}

--- How many bots a server keeps at most, and at least, before its bots stop leaving or arriving.
local MOST, FEWEST = 7, 2

---@class Member
---@field name string
---@field bot Bot
---@field busy boolean Taken out of its brain's loop for a while (a fight, a trip).
---@field leaving boolean On its way out.
---@field stays_until integer? The tick before which it won't travel again (it's just arrived).

--- The bots on this server, by name.
---@type table<string, Member>
crowd.members = {}

-- Bots on their way in: a bot's `player_join` comes before its join's callback.
local joining = {} ---@type table<string, boolean>

---@alias Brain fun(member: Member, random: Random)

---@type Brain?
local brain = nil

--- Sets what this server's bots do: called again and again, in a task, while the bot is here and
--- not busy. It should wait (nf.wait, walk_to) so it doesn't spin.
---@param fn Brain
function crowd.set_brain(fn)
  brain = fn
end

--- Whether `player` is one of the crowd: a bot, which the proxy can't send anywhere.
---@param player Player|string
---@return boolean
function crowd.is_bot(player)
  local name = type(player) == "string" and player or player:name()
  return crowd.members[name] ~= nil or joining[name] == true
end

--- How many bots are here.
---@return integer
function crowd.count()
  local n = 0
  for _ in pairs(crowd.members) do
    n = n + 1
  end
  return n
end

--- The bots here, in name order.
---@return Member[]
function crowd.list()
  local list = {}
  for _, member in pairs(crowd.members) do
    list[#list + 1] = member
  end
  table.sort(list, function(a, b)
    return a.name < b.name
  end)
  return list
end

--- A bot here that isn't busy, at random, or nil.
---@param except string?
---@return Member?
function crowd.idle(except)
  local idle = {}
  for _, member in pairs(crowd.members) do
    if not member.busy and not member.leaving and member.name ~= except then
      idle[#idle + 1] = member
    end
  end
  table.sort(idle, function(a, b)
    return a.name < b.name
  end)
  return #idle > 0 and random:pick(idle) or nil
end

local function live(member)
  nf.task(function()
    while crowd.members[member.name] == member and member.bot:exists() do
      if member.busy or member.leaving or not brain then
        nf.wait(20)
      else
        brain(member, random)
        nf.wait(1)
      end
    end
  end)
end

--- Joins a bot named `name` at `where`, and keeps it busy with this server's brain. `on_joined`
--- is called with it once it's in the world.
---@param name string
---@param where Location|Vec3
---@param on_joined fun(member: Member)?
function crowd.join(name, where, on_joined)
  if crowd.members[name] or joining[name] or nf.players.get(name) then
    return
  end
  joining[name] = true
  nf.bots.join(name, where, nil, function(bot, err)
    joining[name] = nil
    if not bot then
      log("crowd: " .. name .. " couldn't join: " .. tostring(err))
      return
    end
    local member = { name = name, bot = bot, busy = false, leaving = false }
    crowd.members[name] = member
    if on_joined then
      on_joined(member)
    end
    live(member)
  end)
end

-- The crowd's chatter is shared by every server's chat: one line a server every few seconds at
-- most keeps it readable.
local CHATTER_GAP = 20 * 8
local quiet_until = 0

--- Says `text` as the bot, unless another bot here spoke in the last few seconds. True when it did.
---@param member Member
---@param text string
---@return boolean
function crowd.say(member, text)
  local now = nf.server.tick()
  if now < quiet_until then
    return false
  end
  quiet_until = now + CHATTER_GAP
  return member.bot:chat(text)
end

--- Whether a bot may leave for `role`'s server: it's up, this server keeps a few, and that one
--- isn't full (by the population every server shares).
---@param role string
---@return string? server
function crowd.can_travel(role)
  local server = net.server_for(role)
  if not server or server == net.name() or crowd.count() <= FEWEST then
    return nil
  end
  local there = net.population:get(server)
  if there and #(there.bots or {}) >= MOST then
    return nil
  end
  return server
end

--- Sends a bot to `role`'s server: it walks into the portal there and leaves, and that server
--- joins it. Call it in the bot's brain (a task): it returns once the bot has gone.
---@param member Member
---@param role string
---@return boolean went
function crowd.travel(member, role)
  if (member.stays_until or 0) > nf.server.tick() then
    return false
  end
  local server = crowd.can_travel(role)
  local portal = plaza.portal_to(role)
  if not server or not portal then
    return false
  end
  member.leaving = true
  member.bot:chat(random:pick({
    "Off to the " .. role .. "!",
    "See you all later, heading to the " .. role,
    "brb, " .. role,
    "anyone coming to the " .. role .. "?",
  }))
  -- Through the middle: the arena's ring is open only towards the portals.
  member.bot:walk_to(vec3(0.5, plaza.Y, 0.5))
  member.bot:walk_to(portal.front.position)
  member.bot:walk_to(portal.center + vec3(0, 0, random:number(-0.6, 0.6)), { sprint = true })
  -- The far end hears it before the bot leaves here, so it's never on neither.
  net.travel:publish({ name = member.name, from = net.name() }, { server = server })
  net.announce(
    ("<white>%s</white> went from the %s to the %s"):format(member.name, net.role(), role)
  )
  crowd.members[member.name] = nil
  member.bot:leave()
  return true
end

-- The other half of travelling: a bot heading here arrives at the portal it came through.
net.travel:on("message", function(event)
  local name = event.value.name
  local portal = plaza.portal_to(net.role_of(event.server))
  local where = portal and portal.front or plaza.SPAWN
  -- Its old self leaves its server as this one joins: give it a moment so the name is free.
  nf.after(10, function()
    crowd.join(name, where, function(member)
      member.busy = true
      -- Just arrived: it looks around for a minute before it goes anywhere else.
      member.stays_until = nf.server.tick() + 20 * 60
      nf.task(function()
        nf.wait(20)
        member.bot:chat(random:pick({ "made it!", "hi all", "o/", "what did I miss?" }))
        member.busy = false
      end)
    end)
  end)
end)

--- Joins this server's starting bots around the plaza, one every half second.
function crowd.start()
  for i, name in ipairs(crowd.ROSTERS[net.role()]) do
    nf.after(i * 10, function()
      crowd.join(name, plaza.random_spot(random, 3, 9))
    end)
  end
end

nf.on("player_quit", function(event)
  crowd.members[event.player:name()] = nil
end)

return crowd
