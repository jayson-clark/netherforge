nf.commands.register("it-mobs", function()
  local world = nf.worlds.default()
  local base = vec3(196.5, -62, 212.5)
  local pig = world:spawn_entity("minecraft:pig", base)
  local bread = world:spawn_item(base + vec3(2, 0, 0), { kind = "minecraft:bread" })
  local before = pig:attribute("scale") == 1
  local grew = pig:set_attribute_base("scale", 2) and pig:attribute("scale") == 2
  local added = pig:add_attribute_modifier("movement_speed", "it_hurry", 0.5, "add_multiplied_base")
  local ids = {}
  for _, modifier in ipairs(pig:attribute_modifiers("movement_speed")) do
    ids[#ids + 1] = modifier.id .. "/" .. modifier.operation
  end
  local faster = pig:attribute("movement_speed") > pig:attribute_base("movement_speed")
  local removed = pig:remove_attribute_modifier("movement_speed", "it_hurry")
  local unknown = not pcall(function()
    pig:attribute("minecraft:flying_pigs")
  end)
  log(
    "attributes",
    before,
    grew,
    added,
    table.concat(ids, ","),
    faster,
    removed,
    pig:attribute("movement_speed") == pig:attribute_base("movement_speed"),
    tostring(bread.attribute),
    unknown
  )
  bread:remove()
  pig:set_attribute_base("scale", 1)

  local target = base + vec3(0, 0, 5)
  local tries = 0
  local function go()
    tries = tries + 1
    return pig:move_to(target, { speed = 1.5 })
  end
  pig:on("path_end", function(event)
    if event.reached then
      log("walked", (pig:position() - target):length() <= 2)
      pig:remove()
    elseif tries < 5 then
      go()
    else
      log("walked", false)
      pig:remove()
    end
  end)
  -- A mob finds a path only once the game has seen it on the ground: a tick after it spawned.
  nf.after(5, function()
    local off = go()
    log("walking", off, pig:has_path(), pig:path_target() ~= nil)
  end)
end)

local function has_goal(mob, key)
  for _, goal in ipairs(mob:goals() or {}) do
    if goal.key == key then
      return goal
    end
  end
  return nil
end

-- A real iron golem's goals: listed (its targeting goals too, each with its priority), one removed, the rest cleared
-- but one kept; then three goals in Lua: one that walks it 6 blocks along the ground with its
-- own pathfinder, a targeting goal, and one whose tick errors and is taken off.
nf.commands.register("it-goals", function()
  local world = nf.worlds.default()
  local base = vec3(215.5, -62, 195.5)
  local target = base + vec3(0, 0, 6)
  local golem = world:spawn_entity("minecraft:iron_golem", base)
  local listed = golem:goals()
  -- Every goal says its priority, read from the server's own selectors: the game's too.
  local targeting, prioritised = false, true
  for _, goal in ipairs(listed) do
    targeting = targeting or goal.controls[1] == "target"
    prioritised = prioritised and math.type(goal.priority) == "integer"
  end
  local first = listed[1].key
  local removed = golem:remove_goal(first)
  local kept = listed[#listed].key
  local cleared = golem:clear_goals({ keep = { kept } })
  local only_kept = #golem:goals() > 0
  for _, goal in ipairs(golem:goals()) do
    only_kept = only_kept and goal.key == kept
  end
  log(
    "goals",
    #listed > 3,
    targeting,
    prioritised,
    removed,
    has_goal(golem, first) == nil or first == kept,
    cleared,
    only_kept
  )

  local started, ticked, target_started, done = 0, 0, false, false
  local function finish(walked)
    if done then
      return
    end
    done = true
    local walk = has_goal(golem, "showcase:it_walk")
    log(
      "goal run",
      walked,
      started > 0,
      ticked > 0,
      walk ~= nil and walk.priority == 0,
      target_started,
      has_goal(golem, "showcase:it_broken") == nil
    )
    golem:remove()
  end
  local function away(mob)
    local here = mob:position()
    return here ~= nil and (here - target):length() > 1.5
  end
  golem:add_goal("it_walk", {
    priority = 0,
    controls = { "move" },
    should_start = function(mob)
      return mob:is_on_ground() and away(mob)
    end,
    should_continue = function(mob)
      return mob:has_path() and away(mob)
    end,
    start = function(mob)
      started = started + 1
      mob:move_to(target, { speed = 1.5 })
    end,
    tick = function(mob)
      ticked = ticked + 1
    end,
    stop = function(mob)
      if not away(mob) then
        -- Not from inside the AI's own call: the goal comes off as the golem does.
        nf.after(1, function()
          finish(true)
        end)
      end
    end,
  })
  golem:add_goal("it_target", {
    priority = 0,
    controls = { "target" },
    start = function()
      target_started = true
    end,
  })
  golem:add_goal("it_broken", {
    priority = 5,
    controls = { "jump" },
    tick = function()
      error("it-goals boom")
    end,
  })
  nf.after(500, function()
    finish(false)
  end)
end)
