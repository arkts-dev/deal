-- DEAL Standard Library: std/time
-- Provides time-related functions.

local __rt = require("deal.runtime")

local time = {}

--- Returns the current Unix timestamp as an int.
time.now = __rt.function_("()->int", function()
  return __rt.check_int(os.time())
end)

return time
