-- DEAL Standard Library: std/table
-- Provides table/array utility functions.

local __rt = require("deal.runtime")

-- The runtime's private carrier-mark key table (ISSUE-0830): the same
-- table-object keys the emitted chunk, the JSON decoder and the host
-- projection write, so no user string key can shadow or forge one.
local __K = __rt.__keys

local tablelib = {}

--- Returns an array of the table's present string keys, each exactly
-- once (docs/spec-v1.5.md:1909–1912). A present null-valued key is a
-- recorded key whose slot stores no value, so the key list follows the
-- carrier's presence/order discipline, never the stored values: the
-- recorded first-insertion order list [__K.order] first (the emitted
-- chunk's TABLE_NEW / JSON_PARSE carriers keep it, and a first write on
-- a table built outside those paths creates it), then every remaining
-- string key in Lua iteration order — the keys a host or a plain Lua
-- table adds after the crossing. Non-string keys (for example the
-- integer keys of a decoded array carrier) are excluded. The emitted
-- chunk's TABLE_KEYS realization reads exactly this order; the
-- specification pins no order for keys.
-- The wrapper signature carries the canonical descriptor grammar
-- (luajit-v1.2-stdlib-contracts D1): arrays are "[T]". The trailing
-- span triplet carries the call site (ISSUE-0598), so the
-- dynamic-nontable E8001 reports it byte-exact.
tablelib.keys = __rt.function_("(record)->[string]", function(t, file, line, column)
  __rt.check_table(t, file, line, column)
  local result = {}
  local seen = {}
  local order = t[__K.order]
  if type(order) == "table" then
    for i = 1, #order do
      local k = order[i]
      if type(k) == "string" and not seen[k] then
        result[#result + 1] = k
        seen[k] = true
      end
    end
  end
  for k, _ in pairs(t) do
    if type(k) == "string" and not seen[k] then
      result[#result + 1] = k
      seen[k] = true
    end
  end
  return __rt.tag_array(result, "string")
end)

return tablelib
