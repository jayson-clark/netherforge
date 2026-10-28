-- Booth: the web. Web requests to the server, answered by scripts: pages, JSON and webhooks under
-- the package's own path (/showcase/), once the server's owner turns web routes on.
local expo = require("expo")

local hits = nf.data("web_hits")

-- A small page: who's here, and how far they've got.
nf.web.route("GET", "/", function(_, response)
  hits.page = (hits.page or 0) + 1
  local rows = {}
  for _, player in ipairs(nf.players.online()) do
    local seen = 0
    for _ in pairs(expo.seen(player)) do
      seen = seen + 1
    end
    rows[#rows + 1] = ("<li>%s: %d of %d booths</li>"):format(
      nf.text.strip(player:name()),
      seen,
      #expo.ORDER
    )
  end
  response:send(
    200,
    "<!doctype html><title>NetherForge Showcase</title><h1>NetherForge Showcase</h1><p>Answered by a Lua script.</p><ul>"
      .. table.concat(rows)
      .. '</ul><p><a href="stats.json">stats.json</a></p>',
    { ["content-type"] = "text/html; charset=utf-8" }
  )
end)

-- The same as JSON, for a companion site.
nf.web.route("GET", "/stats.json", function(_, response)
  hits.json = (hits.json or 0) + 1
  response:json(
    200,
    { online = #nf.players.online(), booths = #expo.ORDER, page_views = hits.page or 0 }
  )
end)

expo.booth({
  id = "web",
  title = "Web routes",
  summary = "Pages, JSON and webhooks answered by scripts",
  icon = "minecraft:ender_eye",
  lines = {
    '<gold>Web routes</gold>: nf.web.route, behind "requires": { "web": true }.',
    "",
    "<gray>Routes live under the package's path, /showcase/, on the port the",
    "<gray>resource pack is served from. A handler answers now or later (after a",
    "<gray>database query, say): send, json or redirect. Also: webhook signatures",
    '<gray>(verify_signature) and "log in with..." flows (nf.web.oauth).',
    "",
    "<gray>Off until the server's owner sets <white>web.enabled: true</white> in",
    "<gray>plugins/NetherForge/config.yml (the dev server's too).",
  },
  build = function(booth)
    booth:kiosk(4, 6, "<yellow>Where's the page?", function(player)
      local url = nf.web.url("/")
      if url then
        player:send_message(
          ("<gray>Open <aqua><click:open_url:'%s'>%s</click></aqua> in your browser."):format(
            url,
            url
          )
        )
      else
        player:send_message(
          "<gray>Web routes are off on this server: set <white>web.enabled: true</white> in plugins/NetherForge/config.yml and restart."
        )
      end
    end)
    booth:caption(
      4,
      6,
      "<white>/showcase/ and /showcase/stats.json\n<gray>An HTML page and JSON, from two routes.",
      3.2
    )
  end,
})
