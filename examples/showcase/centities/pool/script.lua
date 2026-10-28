local this = this --[[@as Centity]]

-- A pool table, for trying momentum transfer out.
--
-- Left-click the cue ball to play a shot, right-click for a soft one. The shot
-- runs away from where you stand, so walk round the table to aim. Click the
-- table to rerack.
--
-- Every ball is its own physics body. What happens after the break is not
-- scripted anywhere: it is fifteen bodies sharing out one impulse.

local CUE = "ball_cue"

-- Blocks per second added by one shot.
local BREAK = 11.0
local SOFT = 4.0

-- Below this a ball has gone down a pocket.
local POTTED = -0.5

-- Where each ball racks to, by name. Filled in below, as the script starts.
local rack = {}

-- Which balls are down. Locals don't survive a load, but neither does anything
-- physics did: every load puts the balls back where the file says.
local potted = {}

local function say(text)
  local label = this:node("label")
  if label then
    label:set_display_text(text)
  end
end

local function balls()
  local out = {}
  for _, node in ipairs(this:nodes()) do
    local name = node:name()
    if name:sub(1, 5) == "ball_" and name:sub(-5) ~= "_skin" then
      out[#out + 1] = node
    end
  end
  return out
end

-- Where the potted balls wait, well out of the way so a hidden ball can't be
-- collided with where nobody can see it.
local OUT_OF_PLAY = vec3(0, -40, 0)

local function park(node, translation)
  node:set_velocity(vec3.zero)
  node:set_angular_velocity(vec3.zero)
  node:set_rotation(vec3.zero)
  node:set_translation(translation)
end

local function rerack()
  potted = {}
  for _, node in ipairs(balls()) do
    node:set_visible(true)
    park(node, rack[node:name()])
  end
end

-- Physics isn't saved, so when the script starts every ball is at its authored
-- pose: that is the rack. Nudging a ball in the editor moves where it reracks to.
for _, node in ipairs(balls()) do
  rack[node:name()] = node:translation()
end
say(
  "<gray>left-click the cue ball to break · right-click for a soft shot · click the table to rerack"
)

-- Every other tick is plenty to notice a ball going down a pocket.
this:on("tick", function()
  local all = balls()
  local down = 0
  for _, node in ipairs(all) do
    local name = node:name()
    if not potted[name] then
      if node:translation().y < POTTED then
        if name == CUE then
          -- Scratch. Back to the head spot rather than off the table.
          park(node, rack[name])
          say("<red>scratch")
        else
          potted[name] = true
          node:set_visible(false)
          park(node, OUT_OF_PLAY)
        end
      end
    end
    if potted[name] then
      down = down + 1
    end
  end

  if down > 0 and down == #all - 1 then
    say("<gold>cleared · click the table to rerack")
  end
end, { every = 2 })

-- Balls clack when they meet each other or a cushion, louder the harder they
-- hit. Two balls meeting both hear it, so only the one whose name sorts first
-- plays the sound.
local CLACK = "minecraft:block.note_block.hat"

for _, ball in ipairs(balls()) do
  ball:on("collide", function(event)
    if event.speed < 0.5 then
      return
    end
    local other = event.other
    if other ~= nil and other:name() < ball:name() then
      return
    end
    local world = this:world()
    if world ~= nil then
      world:play_sound(CLACK, event.hit_position, {
        volume = math.min(1, event.speed / BREAK),
        pitch = 1.6,
      })
    end
  end)
end

local cue = assert(this:node(CUE))

cue:on("click", function(event)
  -- The cue ball's click goes no further: the table below reracks on anything else.
  event:stop()

  -- The shot runs from the player through the ball. Only the horizontal part
  -- counts: a shot from above would just bury the ball in the cloth.
  local player = event.player:position()
  if player == nil then
    return
  end
  local direction = (cue:world_position() - player):flat()
  if direction:length() < 1e-4 then
    say("<red>you're standing on the ball — no direction to play")
    return
  end

  -- An impulse is mass times a change in speed, so the shot has to be scaled
  -- by what the ball weighs to mean anything in blocks per second.
  local power = (event.click == "left") and BREAK or SOFT
  cue:apply_impulse(direction:normalized() * power * cue:mass())
  say(
    string.format(
      "<white>%s</white> <gray>at</gray> <aqua>%.0f</aqua> <gray>blocks/s</gray>",
      event.click == "left" and "break" or "soft shot",
      power
    )
  )
end)

-- Any other click (the table, another ball) reracks.
this:on("click", function()
  rerack()
  say("<gray>reracked · left-click the cue ball to break")
end)
