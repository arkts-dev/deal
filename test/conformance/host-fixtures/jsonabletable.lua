-- Host Record-content JSON conformance fixture: ordinary objects, nested
-- objects/arrays, a real contextual decode graph and a self-reference for
-- the closed cycle arm. Non-finite leaves substitute null; lone UTF-16 units in
-- object values and keys remain lossless JSON data.

local json = require("deal.json")
local K = require("deal.runtime").__keys

local M = {}

function M.makeTable()
  return { a = 1 }
end

function M.makeNested()
  return { inner = { a = 1 }, xs = { [K.a] = true, [K.n] = 2, 10, 20 } }
end

function M.makeParsed()
  -- Actual contextual Record reconstruction retains binary64 leaves and
  -- private presence/order metadata through the host return.
  return json.decode("{\"v\":0.1,\"w\":1e17}", "record")
end

function M.makeCycle()
  local t = {}
  t.self = t
  return t
end

function M.makeInfinite()
  return { a = 1 / 0 }
end

function M.makeNotANumber()
  return { a = 0 / 0 }
end

function M.makeNestedInfinite()
  return { inner = { a = 1 / 0 } }
end

function M.makeInfiniteElements()
  return { xs = { [K.a] = true, [K.n] = 2, 1 / 0, 2 } }
end

function M.makeBadKey()
  return { [string.char(0xed, 0xa0, 0x80)] = 1 }
end

function M.makeNestedBadKey()
  return { inner = { [string.char(0xed, 0xa0, 0x80)] = 1 } }
end

function M.makeBadString()
  return { a = string.char(0xed, 0xa0, 0x80) }
end

return M
