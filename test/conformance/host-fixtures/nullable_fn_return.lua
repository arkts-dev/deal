-- Host fixture implementation for the host-nullable-function-return-ok/bad
-- conformance tests: the sentinel for null, a raw Lua function for the
-- E8010 path (raw function values fail the function-typed return check).

local rt = require("deal.runtime")

return {
  getCallback = function(mode)
    if mode == "bad" then
      return function(x)
        return x
      end
    end
    return rt.__NULL
  end,
}
