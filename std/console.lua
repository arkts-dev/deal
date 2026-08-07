-- DEAL Standard Library: std/console
-- Provides console output functions.
-- Compiled from std/console.d.deal

local __rt = require("deal.runtime")

local console = {}

--- Print a string to stdout.
console.log = __rt.function_("(string)->void", function(s)
  __rt.check_string(s)
  print(s)
end)

--- Print a string to stdout without trailing newline.
console.write = __rt.function_("(string)->void", function(s)
  __rt.check_string(s)
  io.write(s)
end)

return console
