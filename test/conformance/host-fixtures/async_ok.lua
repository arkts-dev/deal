-- Host fixture implementation for the host-async-ok conformance test. The
-- raw function returns a real async operation whose completion value
-- satisfies the declared async return type string.

local rt = require("deal.runtime")

return {
  fetchValue = function()
    return rt.async_start(function()
      return "fetched"
    end)
  end,
}
