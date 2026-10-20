-- Host fixture implementation for the three-lane arity-extended
-- adapter-over-async conformance test. The declared async export reads
-- the class argument's declared field, invokes the function argument with
-- the declared argument, and returns an operation carrying the declared
-- result. The extra target parameter of an arity-extended call is never
-- forwarded, so it is not a parameter of this function.

local rt = require("deal.runtime")
local K = rt.__keys

local TARGET = "@$external/host.carrierarity/Target"

local M = {}

M.Target = { [K.kind] = "class", [K.classname] = TARGET }
M.Target_defaults = { name = "" }

function M.describeAsync(t, g)
  local gText = "none"
  if type(g) == "function" then
    local ok, value = pcall(g, 41)
    gText = tostring(ok) .. ":" .. tostring(value)
  end
  return rt.async_start(function()
    return "T|" .. tostring(t.name) .. "|" .. gText
  end)
end

return M
