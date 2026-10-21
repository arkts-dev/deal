-- Host fixture implementation for the three-lane @jsonable plain-table
-- content conformance test (ISSUE-0868). Every declared table-typed return
-- is a plain Lua table carrying the JSON-shaped data the class helper's
-- toJson walk accepts: an object, an object with a nested object and an
-- array member, and a self-referential table for the closed cycle arm.

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

return M
