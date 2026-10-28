local this = this --[[@as Dialog]]

-- Every input's answer arrives in event.values, typed: a single_option's picked `id` (a string), a
-- number_range's number and a boolean's "true" or "false" (its onTrue and onFalse).
this:button("send"):on("press", function(event)
  local values = event.values
  event.player:send_message(
    ("<gold>Thanks!</gold> <gray>Favourite: <white>%s</white>, rating <white>%d</white>/10, coming back: <white>%s"):format(
      tostring(values.favourite),
      math.floor(tonumber(values.rating) or 0),
      values.again == "true" and "yes" or "no"
    )
  )
end)

this:button("skip"):on("press", function(event)
  event.player:send_actionbar("<gray>No answers, no problem.")
end)
