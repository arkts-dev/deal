-- DEAL Standard Library: std/console
-- Provides console output functions.

local __rt = require("deal.runtime")

local console = {}

--- Print a string to stdout. The trailing span triplet carries the
-- call site, so the dynamic-nonstring E8001 reports it byte-exact.
console.log = __rt.function_("(string)->null", function(x, file, line, column)
  __rt.check_string(x, file, line, column)
  print(x)
  return __rt.__NULL
end)

--- Print a string to stderr. The trailing span triplet carries the
-- call site, so the dynamic-nonstring E8001 reports it byte-exact.
console.error = __rt.function_("(string)->null", function(x, file, line, column)
  __rt.check_string(x, file, line, column)
  io.stderr:write(x .. "\n")
  return __rt.__NULL
end)

return console
