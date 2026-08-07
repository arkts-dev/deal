-- DEAL Standard Library: std/table
-- Provides table/array utility functions.

local __rt = require("deal.runtime")

local tablelib = {}

--- Returns an array of the table's keys.
tablelib.keys = __rt.function_("(table)->string[]", function(t)
  __rt.check_table(t)
  local result = {}
  for k, _ in pairs(t) do
    result[#result + 1] = k
  end
  return result
end)

--- Returns the number of elements in the table (array part).
tablelib.length = __rt.function_("(table)->int", function(t)
  __rt.check_type("table", t)
  return #t
end)

return tablelib
