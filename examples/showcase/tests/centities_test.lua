-- Tests for the centities booth's exhibits (centities/door, centities/owl), on a fake server.

-- Well away from the expo, so nothing of it is near.
local HERE = vec3(400.5, 100, 400.5)

nf.test.case("the door holds open, then shuts by itself", function()
  local door = assert(nf.centities.spawn("door", HERE))
  door:emit("door:toggle")
  assert(door:is_animation_playing("open"), "a toggle opens it")

  -- `open` takes 0.8s and holds there: still open well after it's done.
  nf.test.advance(30)
  assert(door:is_animation_playing("open"), "it holds open once it has swung")
  assert(not door:is_animation_playing("close"))

  -- Three seconds after it finished opening, it closes.
  nf.test.advance(60)
  assert(not door:is_animation_playing("open"), "it shut by itself")
  assert(door:is_animation_playing("close"))
end)

nf.test.case("a second toggle shuts the door at once, and it stays shut", function()
  local door = assert(nf.centities.spawn("door", HERE))
  door:emit("door:toggle")
  nf.test.advance(5)
  door:emit("door:toggle")
  assert(not door:is_animation_playing("open"))
  assert(door:is_animation_playing("close"), "it swings shut")

  -- The auto-close that the first toggle started doesn't play `close` again.
  nf.test.advance(20)
  assert(not door:is_animation_playing("close"), "close has finished")
  nf.test.advance(80)
  assert(not door:is_animation_playing("close"), "nothing closed it a second time")
  assert(not door:is_animation_playing("open"))
end)

nf.test.case("the owl turns its head to whoever is near", function()
  local owl = assert(nf.centities.spawn("owl", HERE))
  local head = assert(owl:node("head"))
  assert(head:rotation():length() < 1e-6, "it faces front with nobody about")

  local alex = nf.test.player("Alex")
  alex:teleport(HERE + vec3(4, 0, 0))
  nf.test.advance(2)
  assert(head:rotation():length() > 1, "it turned to look at Alex")

  -- Out of sight, it eases back to the front.
  alex:teleport(HERE + vec3(60, 0, 0))
  nf.test.advance(20)
  assert(head:rotation():length() < 1e-3, "it faces front again")
end)
