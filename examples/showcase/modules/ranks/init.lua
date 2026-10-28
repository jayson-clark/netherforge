-- /perm: grant, deny and take back permission nodes, and list what a player
-- has been given. The project may only hand out nodes netherforge.json lists
-- under allow.permissions ("showcase" here: showcase.builder, showcase.vip, ...), and
-- what it grants is kept and given back every time the player joins.
--
-- /perm itself needs showcase.admin, which nobody is granted: on Paper a node
-- nothing declares belongs to operators, so ops and the console can use it.

-- Tries a grant, telling the sender what happened. A node outside
-- allow.permissions is an error, which reaches them as a red line.
local function change(event, verb, apply)
  local target, node = event.arguments.target, event.arguments.node
  local ok, problem = pcall(apply, target, node)
  if ok then
    event.sender:send_message(("<green>%s %s for %s."):format(verb, node, target:name()))
  else
    event.sender:send_message("<red>" .. nf.text.escape(tostring(problem)))
  end
end

local who = {
  { name = "target", type = "player" },
  { name = "node", type = "word" },
}

nf.commands.register("perm", {
  description = "Manage the permissions this project grants",
  permission = "showcase.admin",
  subcommands = {
    grant = {
      description = "Give a player a permission",
      arguments = who,
      handler = function(event)
        change(event, "Granted", function(target, node)
          target:set_permission(node, true)
        end)
      end,
    },
    deny = {
      description = "Take a permission away, even if something else gives it",
      arguments = who,
      handler = function(event)
        change(event, "Denied", function(target, node)
          target:set_permission(node, false)
        end)
      end,
    },
    unset = {
      description = "Forget what this project said about a permission",
      arguments = who,
      handler = function(event)
        local target, node = event.arguments.target, event.arguments.node
        if target:unset_permission(node) then
          event.sender:send_message(("<green>%s no longer sets %s."):format(target:name(), node))
        else
          event.sender:send_message(
            ("<gray>Nothing was set for %s on %s."):format(node, target:name())
          )
        end
      end,
    },
    list = {
      description = "What this project has granted or denied a player",
      arguments = { { name = "target", type = "player" } },
      handler = function(event)
        local target = event.arguments.target
        local set = target:permissions()
        local nodes = {}
        for node in pairs(set) do
          nodes[#nodes + 1] = node
        end
        if #nodes == 0 then
          event.sender:send_message("<gray>" .. target:name() .. " has nothing from this project.")
          return
        end
        -- Granted in green, denied in red, in name order.
        table.sort(nodes)
        for i, node in ipairs(nodes) do
          nodes[i] = (set[node] and "<green>+" or "<red>-") .. node
        end
        event.sender:send_message(target:name() .. ": " .. table.concat(nodes, "<gray>, "))
      end,
    },
  },
})
