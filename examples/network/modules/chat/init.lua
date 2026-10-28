-- Network chat: what anyone says on any server (players and bots alike) is shown on every server,
-- tagged with where it was said. The line is published on the `chat` channel and each server,
-- this one included, shows it when it comes back, so every server shows the same lines in the
-- same order.

local net = require("net")

local chat = {}

--- The last lines heard, newest last.
---@type { server: string, name: string, text: string }[]
chat.recent = {}
local KEEP = 20

--- The line a server shows for something `name` said on `server`.
---@param server string
---@param name string
---@param text string
---@return string
function chat.line(server, name, text)
  return net.tag(server)
    .. " <white>"
    .. net.plain(name)
    .. "</white><gray>:</gray> "
    .. net.plain(text)
end

nf.on("player_chat", function(event)
  -- Said once, through the network: this server shows it when it comes back.
  local sent = net.chat:publish({ name = event.player:name(), text = event.message })
  if sent then
    event:cancel()
  end
end)

net.chat:on("message", function(event)
  local value = event.value
  chat.recent[#chat.recent + 1] = { server = event.server, name = value.name, text = value.text }
  if #chat.recent > KEEP then
    table.remove(chat.recent, 1)
  end
  nf.server.broadcast(chat.line(event.server, value.name, value.text))
end)

return chat
