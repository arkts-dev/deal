-- Host fixture implementation for the host-async-bad conformance test:
-- the operation shape is valid (so no call-site E8010), but the completion
-- value 42 fails the declared async return type string at the await site.

local rt = require("deal.runtime")

return {
  fetchValue = function()
    return rt.async_start(function()
      return 42
    end)
  end,
}
