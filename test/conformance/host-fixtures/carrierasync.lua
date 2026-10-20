-- Host fixture implementation for the three-lane host-carrier async-class
-- conformance test. Every declared class exports its canonical host
-- identity and a <C>_defaults table (construction reads both); a class
-- value crosses to the host as the documented runtime instance (__kind
-- "class", the identity, and the declared top-level fields), a function
-- parameter is the loaded wrapper's plain host function, and the async
-- exports return real backend async operations whose completions are the
-- declared class/array values.

local rt = require("deal.runtime")
local K = rt.__keys

local EP = "@$external/host.carrierasync/Endpoint"
local SC = "@$external/host.carrierasync/ServerConfig"
local FARM = "@$external/host.carrierasync/Farm"

local M = {}

M.Endpoint = { [K.kind] = "class", [K.classname] = EP }
M.Endpoint_defaults = { path = "/" }
M.ServerConfig = { [K.kind] = "class", [K.classname] = SC }
M.ServerConfig_defaults = {
  port = 0,
  endpoint = rt.__MISSING,
  parts = rt.__MISSING,
  tags = rt.__MISSING,
  note = rt.__MISSING,
}
M.Farm = { [K.kind] = "class", [K.classname] = FARM }
M.Farm_defaults = {
  name = "",
  servers = rt.__MISSING,
  primary = rt.__MISSING,
}

local function endpoint(path)
  return { [K.kind] = "class", [K.classname] = EP, path = path }
end

local function config(port, path)
  return {
    [K.kind] = "class",
    [K.classname] = SC,
    port = port,
    endpoint = endpoint(path),
    parts = { "x", "y" },
    note = rt.__NULL,
  }
end

local function arrayText(value)
  if value == rt.__NULL then
    return "null"
  end
  if type(value) == "table" then
    return tostring(#value) .. ":" .. tostring(value[1])
  end
  return "absent"
end

local function textOr(value)
  if value == rt.__NULL then
    return "null"
  end
  if value == nil then
    return "absent"
  end
  return tostring(value)
end

local function describe(s, g)
  local gText = "none"
  if type(g) == "function" then
    local ok, value = pcall(g, 41)
    gText = tostring(ok) .. ":" .. tostring(value)
  end
  return "SC|" .. tostring(s.port) .. "|" .. tostring(s.endpoint.path) ..
    "|" .. arrayText(s.parts) .. "|" .. arrayText(s.tags) ..
    "|" .. textOr(s.note) .. "|" .. gText
end

function M.describeAsync(s, g)
  return rt.async_start(function()
    return describe(s, g)
  end)
end

function M.make()
  return rt.async_start(function()
    return {
      [K.kind] = "class",
      [K.classname] = SC,
      port = 4242,
      endpoint = endpoint("/made"),
      parts = { "a", "b" },
      note = rt.__NULL,
    }
  end)
end

function M.makeAll()
  local shared = config(11, "/one")
  return { shared, shared }
end

function M.makeFarm()
  local shared = config(12, "/two")
  return {
    [K.kind] = "class",
    [K.classname] = FARM,
    name = "farm",
    servers = { shared },
    primary = endpoint("/p"),
  }
end

return M
