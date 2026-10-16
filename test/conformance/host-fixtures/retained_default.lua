-- Host fixture implementation for the legal module-level default-call
-- witness: one retained runtime array carrier is returned by every
-- provider call, and the counter plus the declaration-order trace are
-- invocation-local to one host module load (a fresh process starts at
-- zero, so any leakage is observable in the witness's exact counts).

local rt = require("deal.runtime")
local original = {__a = true, __n = 2, 1, 2}
local calls = 0
local order = ""

return {
  retained = function()
    return original
  end,
  provideFirst = function()
    calls = calls + 1
    order = order .. "A"
    return original
  end,
  provideSecond = function()
    calls = calls + 1
    order = order .. "B"
    return original
  end,
  bump = function()
    calls = calls + 1
    return rt.__NULL
  end,
  calls = function()
    return calls
  end,
  order = function()
    return order
  end,
}
