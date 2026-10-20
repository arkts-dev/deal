-- Host fixture implementation for the three-lane host-carrier class-value
-- conformance test (the ISSUE-0848 acceptance shapes). The class exports
-- its canonical host identity and a <C>_defaults table (construction
-- reads both); a class value crosses to the host as the documented
-- runtime instance (__kind "class", the identity and the declared
-- top-level fields), a function parameter is the loaded wrapper's plain
-- host function, and the asynchronous exports return real backend async
-- operations whose completions are the declared class values. Every
-- produced class carries value = 42 so each assertion proves the declared
-- value survived the crossing rather than a coincidental default.

local rt = require("deal.runtime")
local K = rt.__keys

local VALUE = "@$external/host.carrierclass/Config"
local HOLDER = "@$external/host.carrierclass/Holder"

local M = {}

M.Config = { [K.kind] = "class", [K.classname] = VALUE }
M.Config_defaults = { value = 0 }
M.Holder = { [K.kind] = "class", [K.classname] = HOLDER }
M.Holder_defaults = {
  label = "",
  primary = rt.__MISSING,
  optional = rt.__MISSING,
  maybe = rt.__MISSING,
  items = rt.__MISSING,
}

local function config(value)
  return { [K.kind] = "class", [K.classname] = VALUE, value = value }
end

function M.describeAsync(c)
  return rt.async_start(function()
    return c.value
  end)
end

function M.makeAsync()
  return rt.async_start(function()
    return config(42)
  end)
end

function M.makeSync()
  return config(42)
end

function M.makeAll()
  local shared = config(42)
  return { shared, shared }
end

function M.maybeMake(flag)
  if flag then
    return config(42)
  end
  return rt.__NULL
end

function M.describeOrNull(c)
  if c == nil or c == rt.__NULL then
    return -1
  end
  return c.value
end

function M.maybeMakeAsync(flag)
  return rt.async_start(function()
    return M.maybeMake(flag)
  end)
end

function M.makeHolder()
  return {
    [K.kind] = "class",
    [K.classname] = HOLDER,
    label = "holder",
    primary = config(42),
    optional = rt.__NULL,
    items = { config(42) },
  }
end

function M.consume(cb)
  local ok, result = pcall(cb, config(42))
  if not ok then
    return -2
  end
  if type(result) ~= "table" or result[K.kind] ~= "class" then
    return -3
  end
  return result.value
end

-- A wrong-kind class return: the declared return cell must reject the
-- number with its own exact tuple.
function M.makeWrongKind()
  return 42
end

-- A present language null at a mandatory class return (the runtime's own
-- null sentinel, never the absent nil).
function M.makeNullClass()
  return rt.__NULL
end

-- A wrong declared kind at an array element of a class-element array.
function M.makeWrongElement()
  return { config(42), "junk" }
end

-- A sparse class-element array: slot 2 is missing, so the declared return
-- cell must reject the array rather than truncate it.
function M.makeSparse()
  local sparse = {}
  sparse[1] = config(42)
  sparse[3] = config(42)
  return sparse
end

-- A map-shaped class array: no dense 1-based slots, so the declared return
-- cell must reject the shape rather than silently produce an empty array.
function M.makeMap()
  return { alpha = config(42) }
end

return M
