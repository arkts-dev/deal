-- Host fixture implementation for the host-export-presence conformance test
-- (host-module-abi D6). The execution stage copies this file to
-- <tmp>/host/presence.lua, where the generated loader's verbatim
-- require("host/presence") resolves it via the runner's package.path entry
-- "./?.lua". Host .lua fixtures are never inspected by the $-gate.
--
-- The declared class export must carry the module-qualified identity
-- descriptor (@host.presence/Config) and a <C>_defaults table -- runtime
-- construction through the synthesized class symbol depends on both.

return {
  ping = function()
    return "pong"
  end,

  Config = {
    __kind = "class",
    __classname = "@host.presence/Config",
  },
  Config_defaults = {
    port = 8080,
  },
}
