-- Host fixture implementation for the host-prewrapped-ok conformance
-- test. Both exports carry the exact emitted descriptor strings and
-- conforming behavior; ping's sync ->null return must be the __rt.__NULL
-- sentinel (enforced through the re-wrapped .f).

local rt = require("deal.runtime")

return {
  greet = {
    __kind = "function",
    sig = "(string)->string",
    f = function(name)
      return "hello " .. name
    end,
  },
  ping = {
    __kind = "function",
    sig = "()->null",
    f = function()
      return rt.__NULL
    end,
  },
}
