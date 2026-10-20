-- Host fixture implementation for the three-lane untyped-nested-carrier
-- round-trip conformance test. The host reports the member kinds it
-- observed, invokes the function member with the declared argument, and
-- echoes the container back. The echoed container is a fresh plain table
-- carrying the same member values (the declared table mapping), so the
-- values the host observed are the values DEAL receives back.

local rt = require("deal.runtime")
local K = rt.__keys

local function copyTable(source)
  local out = {}
  for key, value in pairs(source) do
    if type(key) == "string" then
      out[key] = value
    end
  end
  return out
end

local function report(t)
  local kind = "table"
  local fields = "-"
  local member = t.point
  if type(member) == "table" then
    local name = tostring(member[K.classname] or "")
    kind = tostring(string.sub(name, -6) == "/Point")
    fields = tostring(member.x) .. "," .. tostring(member.y)
  end
  local alias = tostring(rawequal(t.point, t.twin))
  local stepText = "none"
  if type(t.step) == "table" and t.step[K.kind] == "function" then
    local ok, value = pcall(t.step.f, 41)
    stepText = tostring(ok) .. ":" .. tostring(value)
  end
  return kind .. "|" .. fields .. "|" .. alias .. "|" .. stepText
end

return {
  roundTrip = function(t)
    local observed = report(t)
    local out = copyTable(t)
    out.observed = observed
    return out
  end,
  roundTripArray = function(items)
    local out = {}
    for index, item in ipairs(items) do
      out[index] = copyTable(item)
    end
    return out
  end,
}
