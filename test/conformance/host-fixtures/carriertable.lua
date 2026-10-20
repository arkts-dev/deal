-- Host fixture implementation for the three-lane host-carrier
-- table-member conformance test. The declared classes export their
-- canonical host identities and defaults; a host-provided function value
-- is the documented wrapper {__kind="function", sig, f}, a class value is
-- the documented runtime instance, and the table-typed return carries a
-- class member and a function member that DEAL reads at their declared
-- types. The table-typed parameter is inspected member by member: the
-- DEAL class member must arrive as a class value with top-level fields,
-- the alias must stay one object, and the DEAL function member must be
-- invocable with its declared result.

local rt = require("deal.runtime")
local K = rt.__keys

local WIDGET = "@$external/host.carriertable/Widget"
local BOARD = "@$external/host.carriertable/Board"

local M = {}

M.Widget = { [K.kind] = "class", [K.classname] = WIDGET }
M.Widget_defaults = {
  label = "",
  weight = 0,
  action = rt.__MISSING,
}
M.Board = { [K.kind] = "class", [K.classname] = BOARD }
M.Board_defaults = {
  title = "",
  widgets = rt.__MISSING,
  meta = rt.__MISSING,
}

local function step(x)
  return 42
end

local function functionValue()
  return { [K.kind] = "function", sig = "(int)->int", f = step }
end

local function widget(label, weight)
  return {
    [K.kind] = "class",
    [K.classname] = WIDGET,
    label = label,
    weight = weight,
    action = functionValue(),
  }
end

function M.describeAsync(w, g)
  local gText = "none"
  if type(g) == "function" then
    local ok, value = pcall(g, 41)
    gText = tostring(ok) .. ":" .. tostring(value)
  end
  return rt.async_start(function()
    return "W|" .. tostring(w.label) .. "|" .. tostring(w.weight) ..
      "|" .. gText
  end)
end

local lastTable = nil

function M.makeTable()
  lastTable = {
    widget = widget("made", 9),
    action = functionValue(),
  }
  return lastTable
end

function M.tableIntact()
  if lastTable == nil then
    return false
  end
  local member = lastTable.widget
  return type(member) == "table" and member[K.kind] == "class"
    and member[K.classname] == WIDGET
end

function M.describeTable(t)
  local point = t.point
  local classed = "false"
  local fields = "-"
  if type(point) == "table" and point[K.kind] == "class" then
    local name = tostring(point[K.classname])
    classed = tostring(string.sub(name, -6) == "/Point")
    fields = tostring(point.x) .. "," .. tostring(point.y)
  end
  local alias = tostring(rawequal(t.point, t.twin))
  local stepText = "none"
  if type(t.step) == "table" and t.step[K.kind] == "function" then
    local ok, value = pcall(t.step.f, 41)
    stepText = tostring(ok) .. ":" .. tostring(value)
  end
  return "T|" .. classed .. "|" .. fields .. "|" .. alias .. "|" .. stepText
end

function M.makeBoard()
  return {
    [K.kind] = "class",
    [K.classname] = BOARD,
    title = "board",
    widgets = { widget("w0", 9) },
    meta = { widget = widget("w1", 5), action = functionValue() },
  }
end

return M
