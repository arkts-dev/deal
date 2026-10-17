-- Host fixture implementation for the legal module-level default-call
-- witness: one retained runtime array carrier is returned by every
-- provider call, and the counter plus the declaration-order trace are
-- invocation-local to one host module load (a fresh process starts at
-- zero, so any leakage is observable in the witness's exact counts).

local rt = require("deal.runtime")
-- The shared runtime array carrier (ISSUE-0830): the array mark and length
-- are keyed by the process-wide private carrier keys, so a DEAL table can
-- never shadow or forge them and the host-built carrier is the same value
-- shape the emitted chunk produces.
local K = rt.__keys
local original = {[K.a] = true, [K.n] = 2, 1, 2}
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
