log("listener starts", nf.config("greeting"))
nf.on("setting_changed", function(event)
  log("heard", event.setting, event.value, event.previous)
end)
