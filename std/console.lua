-- DEAL Standard Library: std/console
-- Provides console output functions.

local __rt = require("deal.runtime")

local console = {}

--- Print a string to stdout.
console.log = __rt.function_("(string)->void", function(s)
  __rt.check_string(s)
  print(s)
end)

--- Print a string to stderr.
console.error = __rt.function_("(string)->void", function(s)
  __rt.check_string(s)
  io.stderr:write(s .. "\n")
end)

return console
