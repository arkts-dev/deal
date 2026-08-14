-- Host fixture implementation for the host-nullable-function-param
-- conformance tests. The adapted function argument is a plain Lua
-- function; the null argument arrives as the __rt.__NULL sentinel.

local rt = require("deal.runtime")

return {
  register = function(cb)
    if cb == nil or cb == rt.__NULL then
      return 0
    end
    return cb(41)
  end,
}
