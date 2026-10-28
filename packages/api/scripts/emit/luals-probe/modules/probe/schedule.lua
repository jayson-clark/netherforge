-- Schedules: daily, weekly and cron, with and without options.
local daily = nf.schedule.daily("18:00", function()
  print("daily")
end, { id = "daily_reward", catch_up = true })
nf.schedule.weekly("sat", "18:00", function() end)
nf.schedule.cron("*/30 * * * *", function() end, { id = "half_hourly" })

local next_run = daily:next_run()
if next_run and daily:is_active() then
  print(nf.time.format(next_run, "yyyy-MM-dd HH:mm"))
end
daily:cancel()
