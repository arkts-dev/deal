-- DEAL Standard Library: std/time
-- Provides time-related functions.

local __rt = require("deal.runtime")

local time = {}

--- Returns the current Unix timestamp in milliseconds as a number.
-- The epoch-millisecond reading uses the existing backend clock
-- resolution (os.time() seconds * 1000) and crosses the declared
-- number return boundary; no int terminal exists (v1.4).
time.nowMillis = __rt.function_("()->number", function()
  return __rt.check_number(os.time() * 1000)
end)

return time
