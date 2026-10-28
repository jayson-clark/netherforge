local this = this --[[@as Menu]]

-- The shop's one script. Each player who opens the shop gets their own window,
-- and their own copy of this script. (Prices are in the file, written with the
-- pack's coin glyph as <glyph:ui/coin>.)

-- What /shop opened this window with (modules/greeter): the nickname the
-- player picked in the welcome dialog, if they did.
local context = this:context() or {}

this:on("open", function(event)
  if context.nickname then
    event.player:send_message(
      "<gold>Welcome to the shop, " .. nf.text.escape(context.nickname) .. "!"
    )
  end
end)

-- The ruby's slot hears its clicks first. It cancels the click and stops it
-- there, so the window's handler below never hears it.
this:slot(13):on("click", function(event)
  event.player:send_message("<red>Rubies are sold out.")
  event:cancel()
  event:stop()
end)

-- Every other click in the window.
this:on("click", function(event)
  if event.in_menu and event.index == 15 then
    event.player:close_menu()
    event:cancel()
  end
end)
