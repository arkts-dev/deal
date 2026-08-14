-- Host fixture implementation for the host-class-export conformance test.
-- Each declared class exports its qualified identity META and a
-- <C>_defaults table (the loader copies both through; construction reads
-- the defaults via the imported-class reference). Optional fields default
-- to the __MISSING sentinel.

local rt = require("deal.runtime")

return {
  Endpoint = {
    __kind = "class",
    __classname = "@host.cfg/Endpoint",
  },
  Endpoint_defaults = {
    path = "/",
  },
  ServerConfig = {
    __kind = "class",
    __classname = "@host.cfg/ServerConfig",
  },
  ServerConfig_defaults = {
    port = 8080,
    endpoint = rt.__MISSING,
    tags = rt.__MISSING,
    note = rt.__MISSING,
  },
  describe = function(s)
    return s.endpoint.path .. ":" .. tostring(s.port)
  end,
}
