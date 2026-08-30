-- DEAL Standard Library: std/table
-- Provides table/array utility functions.

local __rt = require("deal.runtime")

local tablelib = {}

--- Returns an array of the table's string keys in LuaJIT's deterministic
-- iteration order for the table's current state
-- (luajit-v1.2-stdlib-contracts D4). Non-string keys (for example the
-- integer keys of a json.parse array table) are excluded. No shadow
-- insertion-order channel is maintained: table writes stay direct stores
-- with no runtime interception, and the spec pins no order for keys.
-- The wrapper signature carries the canonical descriptor grammar
-- (luajit-v1.2-stdlib-contracts D1): arrays are "[T]".
tablelib.keys = __rt.function_("(table)->[string]", function(t)
  __rt.check_table(t)
  local result = {}
  for k, _ in pairs(t) do
    if type(k) == "string" then
      result[#result + 1] = k
    end
  end
  return result
end)

return tablelib
