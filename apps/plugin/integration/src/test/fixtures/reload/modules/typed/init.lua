nf.commands.register("it-typed", {
  arguments = {
    { name = "count", type = "integer", min = 1, max = 64 },
    { name = "where", type = "position" },
    { name = "mode", type = "choice", choices = { "alpha", "beta" }, default = "alpha" },
    { name = "block", type = "block_state", default = false },
  },
  subcommands = {
    sub = {
      arguments = { { name = "flag", type = "boolean" } },
      handler = function(event)
        log("sub " .. tostring(event.arguments.flag))
      end,
    },
  },
}, function(event)
  local a = event.arguments
  log(
    "typed "
      .. a.count
      .. " "
      .. tostring(a.where)
      .. " "
      .. a.mode
      .. (a.block and " " .. a.block or "")
  )
end)
