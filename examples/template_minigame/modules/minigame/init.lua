-- A minigame in one module: a lobby, a countdown, rounds and a winner.
--
-- Players /arena join into the lobby and are split between two teams. When
-- enough have joined a countdown runs, then a few timed rounds: every death
-- scores a point for the other team, the team with more points wins the
-- round, and the team with more rounds wins the game. Then it's the lobby
-- again.
--
-- What it shows, so you know what to change:
--   * state in plain local variables, moved by ONE clock (nf.every) rather
--     than a timer per phase, so there is nothing to cancel when a phase ends;
--   * teams (nf.teams), a boss bar for the time left and a sidebar for the score;
--   * a custom event ("minigame:finished") other scripts can listen to.
--
-- To use it: Copy into project the module, then change the numbers below.
-- tests/minigame_test.lua shows how to test it without a server.

local MIN_PLAYERS = 2 -- the countdown starts with this many in the lobby
local COUNTDOWN_SECONDS = 10
local ROUNDS = 3
local ROUND_SECONDS = 30

local game = {}

-- "lobby" (waiting for players), "countdown" or "round".
local state = "lobby"
local seconds_left = 0
local round = 0
local last_winner = nil ---@type string?

-- Who is playing: player name -> { player = Player, team = "red" | "blue" }.
local members = {}
-- Points this round and rounds won so far, by team name.
local points = { red = 0, blue = 0 }
local wins = { red = 0, blue = 0 }

-- A team is made in the module's body and belongs to it: a reload removes it
-- with the module, and the module makes it again.
local teams = {
  red = nf.teams.create("red", { prefix = "<red>[Red] ", color = "red", friendly_fire = false }),
  blue = nf.teams.create(
    "blue",
    { prefix = "<blue>[Blue] ", color = "blue", friendly_fire = false }
  ),
}
local OTHER = { red = "blue", blue = "red" }

local bar = nf.bossbars.create({ text = "<yellow>Waiting for players", color = "yellow" })

local function count(team)
  local n = 0
  for _, member in pairs(members) do
    if team == nil or member.team == team then
      n = n + 1
    end
  end
  return n
end

-- Draws what every player sees: the sidebar and the boss bar.
local function refresh()
  local lines
  if state == "lobby" then
    lines = { "<gray>Waiting for players", ("<white>%d / %d"):format(count(), MIN_PLAYERS) }
    bar:set_text("<yellow>Waiting for players")
    bar:set_progress(1)
  elseif state == "countdown" then
    lines = { "<yellow>Starting in " .. seconds_left }
    bar:set_text("<yellow>Starting in " .. seconds_left)
    bar:set_progress(seconds_left / COUNTDOWN_SECONDS)
  else
    lines = {
      ("<gold>Round %d of %d"):format(round, ROUNDS),
      ("<red>Red: %d  <gray>(%d won)"):format(points.red, wins.red),
      ("<blue>Blue: %d  <gray>(%d won)"):format(points.blue, wins.blue),
      "<gray>" .. seconds_left .. "s left",
    }
    bar:set_text(("<gold>Round %d: %ds left"):format(round, seconds_left))
    bar:set_progress(seconds_left / ROUND_SECONDS)
  end
  for _, member in pairs(members) do
    member.player:sidebar():set_title("<gold><b>Arena")
    member.player:sidebar():set_lines(lines)
  end
end

local function broadcast(text)
  for _, member in pairs(members) do
    member.player:send_message(text)
  end
end

-- Back to waiting. Players stay in their teams, so a full lobby plays again.
local function to_lobby()
  state = "lobby"
  round = 0
  points = { red = 0, blue = 0 }
  wins = { red = 0, blue = 0 }
  if count() >= MIN_PLAYERS then
    state = "countdown"
    seconds_left = COUNTDOWN_SECONDS
  end
  refresh()
end

local function finish()
  local winner = nil
  if wins.red ~= wins.blue then
    winner = wins.red > wins.blue and "red" or "blue"
  end
  last_winner = winner
  broadcast(winner and ("<gold>" .. winner .. " wins the game!") or "<gold>A draw!")
  -- Other scripts can react: pay a prize, record the result, ...
  nf.emit("minigame:finished", { winner = winner })
  to_lobby()
end

local function end_round()
  if points.red ~= points.blue then
    local winner = points.red > points.blue and "red" or "blue"
    wins[winner] = wins[winner] + 1
    broadcast("<gold>" .. winner .. " takes round " .. round .. "!")
  else
    broadcast("<gray>Round " .. round .. " is a draw.")
  end
  if round >= ROUNDS then
    finish()
    return
  end
  round = round + 1
  points = { red = 0, blue = 0 }
  seconds_left = ROUND_SECONDS
  refresh()
end

-- The clock: once a second, whatever the state.
nf.every(20, function()
  if state == "lobby" then
    return
  end
  seconds_left = seconds_left - 1
  if seconds_left > 0 then
    refresh()
  elseif state == "countdown" then
    state = "round"
    round = 1
    seconds_left = ROUND_SECONDS
    broadcast("<green>Go!")
    refresh()
  else
    end_round()
  end
end)

--- Puts a player in the lobby, on the smaller team. False and why not when a game is on.
---@param player Player
---@return boolean joined
---@return string? problem
function game.join(player)
  if state == "round" then
    return false, "A round is running: wait for the next game."
  end
  if members[player:name()] then
    return false, "You are already in."
  end
  local team = count("red") <= count("blue") and "red" or "blue"
  members[player:name()] = { player = player, team = team }
  teams[team]:add_member(player)
  bar:show_to(player)
  if state == "lobby" and count() >= MIN_PLAYERS then
    state = "countdown"
    seconds_left = COUNTDOWN_SECONDS
  end
  refresh()
  return true
end

--- Takes a player out. A game left with one team ends.
---@param player Player
function game.leave(player)
  local member = members[player:name()]
  if not member then
    return
  end
  members[player:name()] = nil
  teams[member.team]:remove_member(player)
  bar:hide_from(player)
  player:sidebar():clear()
  if state == "countdown" and count() < MIN_PLAYERS then
    state = "lobby"
  elseif state == "round" and (count("red") == 0 or count("blue") == 0) then
    broadcast("<gray>Not enough players: the game is over.")
    to_lobby()
    return
  end
  refresh()
end

---@return "lobby"|"countdown"|"round"
function game.state()
  return state
end

--- Which team a player plays for, if they are in.
---@param player Player
---@return string?
function game.team_of(player)
  local member = members[player:name()]
  return member and member.team
end

--- The points this round, the rounds won so far (by team name) and the round number.
function game.score()
  return { points = points, wins = wins, round = round }
end

--- The last game's winning team, or nil for a draw (or no game yet).
---@return string?
function game.last_winner()
  return last_winner
end

-- The scoring rule: a death in a round is a point for the other team.
nf.on("player_death", function(event)
  local member = members[event.player:name()]
  if state == "round" and member then
    local other = OTHER[member.team]
    points[other] = points[other] + 1
    refresh()
  end
end)

nf.on("player_quit", function(event)
  game.leave(event.player)
end)

nf.commands.register("arena", {
  description = "Join or leave the arena minigame",
  players_only = true,
  subcommands = {
    join = {
      description = "Join the lobby",
      handler = function(event)
        local player = assert(event.player) -- players_only: never the console
        local joined, problem = game.join(player)
        player:send_message(joined and "<green>You joined the arena." or "<red>" .. problem)
      end,
    },
    leave = {
      description = "Leave the arena",
      handler = function(event)
        game.leave(assert(event.player))
      end,
    },
  },
})

return game
