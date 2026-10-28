-- Booth: integrations. What reaches beyond the server: HTTP requests, streams and WebSockets to
-- the hosts a package declares, and other plugins (Vault's economy, PlaceholderAPI) by name. The
-- showcase doesn't declare these, since the dev server has neither the plugins nor a service to
-- call; the library declares one host, to show where it goes.
local expo = require("expo")

expo.booth({
  id = "integrations",
  title = "HTTP & other plugins",
  summary = "Web services, WebSockets, Vault and PlaceholderAPI",
  icon = "minecraft:tripwire_hook",
  lines = {
    "<gold>Reaching out</gold>",
    "",
    "<white>nf.http.request</white> <gray>calls a web service: a Discord webhook, a status API.",
    "<white>nf.http.stream</white><gray>, <white>nf.http.websocket</white><gray>: streamed answers and live sockets.",
    '<gray>Each host is declared: "requires": { "http": ["discord.com"] } (the library',
    "<gray>declares api.example.com). The owner decides about private addresses.",
    '<white>nf.economy</white> <gray>players\' money through Vault: "plugins": ["vault"].',
    "<white>nf.placeholders</white> <gray>fill %placeholders% and offer your own, through",
    '<gray>PlaceholderAPI: "plugins": ["placeholderapi"].',
    "",
    "<gray>/nf requires lists what every package on the server may do.",
  },
  build = function(booth)
    booth:placard(
      5,
      1.6,
      6,
      table.concat({
        "<gold>A webhook, in four lines</gold>",
        "<gray>nf.http.request({",
        '<gray>  method = "POST", url = WEBHOOK,',
        '<gray>  json = { content = "Someone finished the tour!" },',
        "<gray>}, function(response, err) ... end)",
      }, "\n"),
      { width = 300, turns = true }
    )
  end,
})
