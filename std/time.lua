-- DEAL Standard Library: std/time
-- Provides time-related functions.

local __rt = require("deal.runtime")

local time = {}

--- Returns the current Unix timestamp in milliseconds as an int.
time.nowMillis = __rt.function_("()->int", function()
  return __rt.check_int(os.time() * 1000)
end)

return time
