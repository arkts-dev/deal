-- DEAL Standard Library: std/console
-- Provides console output functions.
-- Compiled from std/console.d.deal

local __rt = require("deal.runtime")

local console = {}

--- Print a string to stdout.
function console.log(s)
  __rt.check_string(s)
  print(s)
end

--- Print a string to stdout without trailing newline.
function console.write(s)
  __rt.check_string(s)
  io.write(s)
end

return console
