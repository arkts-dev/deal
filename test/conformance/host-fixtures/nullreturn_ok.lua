-- Host fixture implementation for the host-null-return-ok conformance
-- test. Host fixtures may require("deal.runtime") (documented). Null
-- results MUST be the __rt.__NULL sentinel: plain Lua nil (or zero
-- results) raises E8010 under the presence rule.

local rt = require("deal.runtime")

return {
  ping = function()
    return rt.__NULL
  end,
}
