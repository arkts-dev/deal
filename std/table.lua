-- DEAL Standard Library: std/table
-- Provides table/array utility functions.

local __rt = require("deal.runtime")

local tablelib = {}

--- Returns an array of the table's string keys.
-- Numeric and other key types are converted to strings via tostring().
tablelib.keys = __rt.function_("(table)->string[]", function(t)
  __rt.check_table(t)
  local result = {}
  for k, _ in pairs(t) do
    result[#result + 1] = tostring(k)
  end
  return result
end)

return tablelib
