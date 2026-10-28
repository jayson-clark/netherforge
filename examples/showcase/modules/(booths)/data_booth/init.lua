-- Booth: saved data. Everything a script can keep: a player's saved table, a module's nf.data
-- table, the project's own SQLite database (migrations/), files in the data folder, and schedules
-- that run at real times of day.
local expo = require("expo")

local data_booth = {}

local db = nf.db()
local counters = nf.data("expo_counters")
local board ---@type Centity?

-- The guestbook's last lines, read from the database (a task waits for the answer).
local function redraw()
  nf.task(function()
    local rows = db:query("SELECT player, line FROM guestbook ORDER BY id DESC LIMIT 6") or {}
    local lines = { "<gold>Guestbook</gold> <gray>(the project's database)" }
    for _, row in ipairs(rows) do
      lines[#lines + 1] = ("<white>%s</white><gray>: %s"):format(
        nf.text.escape(row.player),
        nf.text.escape(row.line)
      )
    end
    if #rows == 0 then
      lines[#lines + 1] = "<gray>Nobody has signed yet."
    end
    local node = board and board:exists() and board:node("text")
    if node then
      node:set_display_text(table.concat(lines, "\n"))
    end
  end)
end

--- Writes a line to the guestbook (the guestbook dialog calls this), and to a file for good measure.
---@param player Player
---@param line string
function data_booth.sign(player, line)
  db:execute(
    "INSERT INTO guestbook (player, line, at) VALUES (?, ?, ?)",
    { player:name(), line, nf.server.unix_time() },
    function(_, err)
      if err then
        player:send_message("<red>Couldn't sign: " .. err)
        return
      end
      player:send_message(
        "<green>Signed. <gray>It's in the database, and in plugins/NetherForge/data/guestbook.txt."
      )
      redraw()
    end
  )
  nf.files.get("guestbook.txt"):append(
    ("%s %s: %s\n"):format(
      nf.time.format(nf.server.unix_time(), "yyyy-MM-dd HH:mm"),
      player:name(),
      line
    )
  )
end

-- A schedule: every day at noon, real time, everyone online hears how many signed today.
nf.schedule.daily("12:00", function()
  nf.server.broadcast("<gold>Noon at the expo. <gray>Sign the guestbook in the data booth!")
end, { id = "expo_noon" })

expo.booth({
  id = "data",
  title = "Saved data & databases",
  summary = "Saved tables, a database with migrations, files and schedules",
  icon = "minecraft:bookshelf",
  lines = {
    "<gold>Keeping things</gold>",
    "",
    "<white>player:data()</white> <gray>a table saved with each player (your nickname, booths seen).",
    "<white>nf.data(name)</white> <gray>a table saved for a module (the counter below).",
    "<white>nf.db()</white> <gray>the project's own SQLite database; migrations/*.sql make its",
    "<gray>tables, once each, in order. Queries run off the main thread.",
    "<white>nf.files</white> <gray>text and JSON files in the data folder.",
    "<white>nf.schedule</white> <gray>runs at real times: daily at noon, here.",
    "<white>centity:data()</white><gray>, <white>block:data()</white><gray>, an item's `data`: saved per thing.",
  },
  build = function(booth)
    board = booth:placard(7, 1.5, 6, "<gray>…", { width = 280 })
    redraw()
    booth:kiosk(3, 3, "<yellow>Sign the guestbook", function(player)
      player:open_dialog("guestbook")
    end)
    booth:caption(3, 3, "<white>Guestbook\n<gray>A dialog's text, into the database.", 3.2)
    local counter =
      booth:placard(3, 3.3, 9, "", { scale = 0.8, turns = true, alignment = "center" })
    local function show()
      local node = counter and counter:node("text")
      if node then
        node:set_display_text(
          ("<white>Pressed <gold>%d</gold> times\n<gray>nf.data: kept across restarts"):format(
            counters.presses or 0
          )
        )
      end
    end
    show()
    booth:kiosk(3, 9, "<yellow>Press me", function(player)
      counters.presses = (counters.presses or 0) + 1
      show()
      local mine = player:data()
      mine.presses = (mine.presses or 0) + 1
      player:send_actionbar(
        ("<gray>You've pressed it %d times (your own saved table)."):format(mine.presses)
      )
    end)
  end,
})

return data_booth
