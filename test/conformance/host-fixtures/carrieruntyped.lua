-- Host fixture implementation for the three-lane untyped-nested-carrier
-- round-trip conformance test. The host reports the member kinds it
-- observed (the DEAL class instance identity and top-level fields, the
-- function wrapper and its invocation result, and the builtin Error
-- instance identity with its code/message), and echoes the container
-- back. The echoed container is a fresh plain table carrying the same
-- member values (the declared table mapping), so the values the host
-- observed are the values DEAL receives back. The array export reports
-- each element's class member the same way and the observed alias
-- identity between the elements.

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
  local kind = "false"
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
  local errText = "none"
  local err = t.err
  if type(err) == "table" and err[K.kind] == "class"
      and err[K.classname] == "Error" then
    errText = "true:" .. tostring(err.code) .. ":" .. tostring(err.message)
  end
  return kind .. "|" .. fields .. "|" .. alias .. "|" .. stepText
    .. "|" .. errText
end

-- One array element's class member as the host observes it: the declared
-- class identity and the top-level declared fields. A member left in the
-- DEAL chunk representation carries no host-readable class tag and no
-- top-level field, so it reports "false|-" here.
local function reportElement(item)
  local member = item.point
  if type(member) ~= "table" then
    return "false|-"
  end
  local name = tostring(member[K.classname] or "")
  return tostring(string.sub(name, -6) == "/Point")
    .. "|" .. tostring(member.x) .. "," .. tostring(member.y)
end

return {
  roundTrip = function(t)
    local observed = report(t)
    local out = copyTable(t)
    out.observed = observed
    return out
  end,
  roundTripArray = function(items)
    local firstPoint = nil
    if type(items[1]) == "table" then
      firstPoint = items[1].point
    end
    local out = {}
    for index, item in ipairs(items) do
      local entry = copyTable(item)
      local alias = index == 1 and "self"
        or tostring(rawequal(item.point, firstPoint))
      entry.observed = reportElement(item) .. "|" .. alias
      out[index] = entry
    end
    return out
  end,
}
