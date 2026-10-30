-- Host fixture implementation for the three-lane @jsonable plain-table
-- content conformance test (ISSUE-0868). Every declared table-typed return
-- is a plain Lua table carrying the JSON-shaped data the class helper's
-- toJson walk accepts: an object, an object with a nested object and an
-- array member, the deployed std/json decode graph (makeParsed, ISSUE-0900:
-- the decoder's own table with only its per-slot numeric-variant marks
-- besides the content keys), and a self-referential table for the closed
-- cycle arm. The closed arms' returns carry a non-finite numeric leaf, an
-- invalid UTF-8 object value and an invalid UTF-8 object key, the content
-- shapes the finite acyclic JSON domain excludes.

local json = require("std.json")
local K = require("deal.runtime").__keys

local M = {}

function M.makeTable()
  return { a = 1 }
end

function M.makeNested()
  return { inner = { a = 1 }, xs = { [K.a] = true, [K.n] = 2, 10, 20 } }
end

function M.makeParsed()
  -- The deployment's own std/json decode: the returned table carries only
  -- its content keys beside the decoder's private per-slot [__K.nK]
  -- numeric-variant marks, so a class table field/element holding it walks
  -- by the content keys and spells the marked slots by their variant.
  return json.parse.f("{\"v\":0.1,\"w\":1e17}")
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
  return { [string.char(0xFF)] = 1 }
end

function M.makeNestedBadKey()
  return { inner = { [string.char(0xFF)] = 1 } }
end

function M.makeBadString()
  return { a = string.char(0xFF) }
end

return M
