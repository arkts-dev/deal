-- Host fixture implementation for the three-lane @jsonable plain-table
-- content conformance test (ISSUE-0868). Every declared table-typed return
-- is a plain Lua table carrying the JSON-shaped data the class helper's
-- toJson walk accepts: an object, an object with a nested object and an
-- array member, and a self-referential table for the closed cycle arm.
-- The closed arms' returns carry a non-finite numeric leaf and an invalid
-- UTF-8 object key, the two content shapes the finite acyclic JSON domain
-- excludes.

local M = {}

function M.makeTable()
  return { a = 1 }
end

function M.makeNested()
  return { inner = { a = 1 }, xs = { 10, 20 } }
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
  return { xs = { 1 / 0, 2 } }
end

function M.makeBadKey()
  return { [string.char(0xFF)] = 1 }
end

function M.makeNestedBadKey()
  return { inner = { [string.char(0xFF)] = 1 } }
end

return M
