-- Host fixture implementation for the host-nullable-return-ok/bad
-- conformance tests. The sentinel is the only null representation; an int
-- return exercises the wrong-representation E8010 path.

local rt = require("deal.runtime")

return {
  find = function(s)
    if s == "__NULL__" then
      return rt.__NULL
    end
    if s == "__BAD__" then
      return 42 -- wrong representation for string | null
    end
    return s
  end,
}
