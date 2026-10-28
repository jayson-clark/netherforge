-- Tests for the greeter module, run by `netherforge test` (or the editor's Run tests) on a fake
-- server: every test starts a fresh one with this project loaded and its scripts running.
local messages = require("greeter.messages")

nf.test.case("the welcome names the player and escapes the greeting", function()
  assert(messages.welcome("Hi <b>", "Alex") == "<green>" .. nf.text.escape("Hi <b>") .. ", Alex!")
end)

nf.test.case("a first join marks the player welcomed, and a later one leaves that be", function()
  local alex = nf.test.player("Alex")
  assert(alex:data().greeter.welcomed == true, "the greeter should have welcomed Alex")

  -- Another join event, as when they come back: the welcome dialog is for the first one only.
  local before = alex:data().greeter
  nf.test.raise(
    "player_join",
    { player = alex, first_join = false, message = "Alex joined the game" }
  )
  assert(alex:data().greeter == before)
end)

nf.test.case("a join counts a visit in the project's own database", function()
  nf.test.player("Alex")
  -- The database works off the main thread: its writes are done a tick or two later.
  nf.test.advance(2)

  local joins
  nf.task(function()
    local rows = assert(nf.db():query("SELECT joins FROM visits WHERE player = ?", { "Alex" }))
    joins = rows[1] and rows[1].joins
  end)
  nf.test.advance(2)
  assert(joins == 1, "Alex should have joined once, the database says " .. tostring(joins))
end)
