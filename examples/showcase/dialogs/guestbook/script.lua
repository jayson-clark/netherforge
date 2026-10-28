local this = this --[[@as Dialog]]

-- The guestbook's one button hands the line to the data booth's module, which writes it to the
-- database (migrations/002_guestbook.sql) and redraws the booth's list.
local data_booth = require("data_booth")

this:button("sign"):on("press", function(event)
  local line = tostring(event.values.line or "")
  if line == "" then
    event.player:send_actionbar("<gray>Nothing written, nothing kept.")
    return
  end
  data_booth.sign(event.player, line)
end)
