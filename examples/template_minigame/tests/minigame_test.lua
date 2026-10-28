-- Tests for the minigame module, run by `netherforge test` (or the editor's Run tests).
-- Every test starts a fresh fake server with the project loaded, so each one starts in the lobby.
local game = require("minigame")

-- Two players in the lobby, the countdown run out: a round is on.
local function start_round()
  local alex = nf.test.player("Alex")
  local blake = nf.test.player("Blake")
  assert(game.join(alex))
  assert(game.join(blake))
  nf.test.advance(10 * 20)
  return alex, blake
end

nf.test.case("players are split between two teams", function()
  local alex = nf.test.player("Alex")
  local blake = nf.test.player("Blake")
  game.join(alex)
  game.join(blake)
  assert(game.team_of(alex) ~= game.team_of(blake), "two players should be on different teams")
  local team = assert(nf.teams.get(assert(game.team_of(alex))))
  assert(team:has_member(alex))
end)

nf.test.case("the countdown starts with enough players, and runs into a round", function()
  local alex = nf.test.player("Alex")
  game.join(alex)
  assert(game.state() == "lobby", "one player is not enough")

  game.join(nf.test.player("Blake"))
  assert(game.state() == "countdown")
  assert(alex:sidebar():lines()[1] == "<yellow>Starting in 10")

  nf.test.advance(9 * 20)
  assert(game.state() == "countdown", "a second to go")
  nf.test.advance(20)
  assert(game.state() == "round")
  assert(game.score().round == 1)
end)

nf.test.case("a death scores for the other team", function()
  local alex, blake = start_round()
  nf.test.raise(
    "player_death",
    { player = alex, cause = "entity_attack", drops = {}, keep_inventory = false }
  )
  local scores = game.score().points
  assert(scores[assert(game.team_of(blake))] == 1, "the team that didn't die scores")
  assert(scores[assert(game.team_of(alex))] == 0)
end)

nf.test.case("the team with most rounds wins the game, and the lobby starts again", function()
  local finished
  nf.on("minigame:finished", function(event)
    finished = event
  end)
  local alex, blake = start_round()
  local winner = game.team_of(blake)

  -- Alex dies in each round, so Blake's team takes all three.
  for _ = 1, 3 do
    nf.test.raise(
      "player_death",
      { player = alex, cause = "fall", drops = {}, keep_inventory = false }
    )
    nf.test.advance(30 * 20)
  end
  assert(finished, "the game should have finished")
  assert(finished.winner == winner and game.last_winner() == winner)
  assert(game.state() == "countdown", "two players are enough to play again")
  assert(game.score().wins[winner] == 0, "the score starts again")
end)

nf.test.case("nobody joins a game in progress, and a lone team ends it", function()
  local alex, blake = start_round()
  assert(game.join(nf.test.player("Casey")) == false)

  game.leave(blake)
  assert(game.state() == "lobby", "one player left is not a game")
  assert(game.team_of(blake) == nil)
  assert(game.join(alex) == false, "Alex is still in")
end)
