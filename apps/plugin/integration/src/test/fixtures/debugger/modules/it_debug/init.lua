-- The debugger scenario's script: a breakpoint goes on the log line.
local ticks = 0
nf.on("tick", function()
  ticks = ticks + 1
  if ticks % 20 == 0 then
    log("debugged", ticks)
  end
end)
