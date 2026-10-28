-- Booth: dialogs. Minecraft's dialog screens from dialogs/<id>/dialog.json: notices,
-- confirmations and multi-action dialogs, with text, items, inputs and buttons, each with a script;
-- and dialogs a script builds and waits on in a task.
local expo = require("expo")

-- A dialog no file has, made from a table. `ask` pauses a task until the player answers.
local quiz = nf.dialogs.create({
  title = "<gold>Quick question",
  type = "multi_action",
  body = { { type = "message", text = "<gray>Which is a NetherForge resource kind?" } },
  buttons = {
    { key = "centity", label = "Centities" },
    { key = "creeper", label = "Creepers" },
    { key = "redstone", label = "Redstone" },
  },
})

expo.booth({
  id = "dialogs",
  title = "Dialogs",
  summary = "Notices, confirmations, forms, and dialogs a script waits on",
  icon = "minecraft:writable_book",
  lines = {
    "<gold>Dialogs</gold> live in dialogs/<id>/dialog.json, with a script.",
    "",
    "<white>notice</white> <gray>one button: the welcome you saw on joining, with a text input.",
    "<white>confirmation</white> <gray>yes or no: the portals to other worlds ask one.",
    "<white>multi_action</white> <gray>a row of buttons, and inputs of every kind: text,",
    "<gray>single option, number range and checkbox, arriving typed in event.values.",
    "",
    "<gray>A dialog can also be built in code (nf.dialogs.create) and asked in a",
    "<gray>task (dialog:ask), which waits for the answer like a function call.",
  },
  build = function(booth)
    booth:kiosk(5, 2, "<yellow>Welcome (notice)", function(player)
      player:open_dialog("welcome", { values = { nickname = player:name() } })
    end)
    booth:caption(5, 2, "<white>Notice\n<gray>A text input; Done saves your nickname.", 3.2)
    booth:kiosk(5, 5, "<yellow>Survey (multi action)", function(player)
      player:open_dialog("survey")
    end)
    booth:caption(5, 5, "<white>Every input kind\n<gray>Your answers come back typed.", 3.2)
    booth:kiosk(5, 8, "<yellow>Portal (confirmation)", function(player)
      player:open_dialog(
        "portal",
        { context = "hub", body = { where = "<gray>(This one only goes back to the hub.)" } }
      )
    end)
    booth:caption(5, 8, "<white>Confirmation\n<gray>The opening's context says where Go goes.", 3.2)
    booth:kiosk(5, 11, "<yellow>Quiz (dialog:ask)", function(player)
      nf.task(function()
        local press = quiz:ask(player)
        if not press then
          player:send_message("<gray>No answer: the dialog was closed.")
        elseif press.key == "centity" then
          player:send_message("<green>Right! <gray>(The script waited on your answer in a task.)")
        else
          player:send_message("<red>Not quite. <gray>Look around the expo for a hint.")
        end
      end)
    end)
    booth:caption(5, 11, "<white>Built in code\n<gray>A task waits on your answer.", 3.2)
  end,
})
