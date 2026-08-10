-- DEAL Standard Library: std/console
-- Provides console output functions.

local __rt = require("deal.runtime")

local console = {}

--- Print a string to stdout.
console.log = __rt.function_("(string)->null", function(x)
  __rt.check_string(x)
  print(x)
  return __rt.__NULL
end)

--- Print a string to stderr.
console.error = __rt.function_("(string)->null", function(x)
  __rt.check_string(x)
  io.stderr:write(x .. "\n")
  return __rt.__NULL
end)

return console
