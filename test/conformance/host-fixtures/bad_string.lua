-- Host-module fixture (host-module-abi D6): a host surface whose returned
-- strings exercise the DEAL v1.2 string carrier classes.  The declared
-- string boundary rejects the malformed and surrogate code point encodings
-- and admits the valid ones; the raw carrier decodes at scalar and array
-- element positions (including nested and nullable element positions). The
-- nullable nested-array export carries one explicit null element, which must
-- stay the language null, the shared-nested-array export returns the same
-- inner array twice (one DEAL array observed through both aliases), and the
-- nested-tail export is rejected at its later element, so its earlier
-- elements must stay the raw host values. The native-* exports return the
-- same declared shapes through the backend-native array carrier (one plain
-- Lua table here; the JVM host's declared $DealRt wrapper there), so every
-- valid control must land as a usable DEAL array at every nesting level
-- and every invalid element must be rejected by the element check.

local rt = require("deal.runtime")

local M = {}

-- Returns a string whose bytes are invalid UTF-8:
-- 0x61 (a), then a stray continuation byte 0x80, then 0x62 (b).
function M.badString()
  return "a\x80b"
end

-- Returns a string containing a UTF-16 surrogate code point encoding
-- (U+D800 as CESU-style bytes ED A0 80) — never a valid scalar value.
function M.surrogateString()
  return "\xED\xA0\x80"
end

-- Returns "a" .. U+D800 .. "b" — the surrogate code point encoding is
-- embedded mid-string, so the reason selection must follow the offending
-- code point, never the carrier's length.
function M.embeddedSurrogateString()
  return "a\xED\xA0\x80b"
end

-- Returns a scalar-valid string (the valid control).
function M.validString()
  return "ok"
end

-- Returns a declared string[] whose sole element is the valid raw carrier:
-- the element check must project the decoded string into the production
-- array so ordinary DEAL reads observe it.
function M.rawStringArray()
  return { "ok" }
end

-- Returns a declared string[][] whose nested element is the valid raw
-- carrier: the element projection recurses through the nested position.
function M.rawNestedStringArray()
  return { { "ok" } }
end

-- Returns a declared (string | null)[] whose sole element is the valid raw
-- carrier: the element projection recurses through the nullable position.
function M.rawNullableStringArray()
  return { "ok" }
end

-- Returns a declared (string[] | null)[] carrying one explicit null and one
-- nested array: the null element must project to the language null (the
-- deployed runtime's sentinel normalizes to the chunk's own, never an empty
-- array carrier) and the nested array's element must stay readable.
function M.rawNullableArrayArray()
  return { rt.__NULL, { "ok" } }
end

-- Returns a declared string[][] whose two elements are the same inner host
-- array: the projection maps the one raw host table to one DEAL array, so a
-- DEAL identity comparison of the two elements is false and a mutation
-- through either alias is observed through the other.
function M.sharedNestedStringArray()
  local shared = { "ok" }
  return { shared, shared }
end

-- Returns a declared string[][] whose second element is not an array: the
-- declared return is rejected with the element arm, and the retained first
-- element must stay the raw host value (a Lua string is the raw carrier
-- here, so a failed return projects nothing).
function M.badNestedTail()
  return { { "ok" }, "not an array" }
end

-- Returns a declared string[] whose second element is the U+D800 surrogate
-- code point encoding: the raw host array carrier must never stand in for
-- the element check, so the return is rejected with the element arm and the
-- pinned host string-carrier reason as its inner message.
function M.nativeBadStringArray()
  return { "ok", "\xED\xA0\x80" }
end

-- Returns a declared string[][] whose first element is a valid nested array
-- and whose second element is not an array: the recursive element check
-- rejects the later element after the earlier nested position was checked,
-- and the earlier raw host value must stay untouched.
function M.nativeBadNestedTail()
  return { { "ok" }, "not an array" }
end

-- Returns a declared string[] through the backend-native array carrier (the
-- Lua host has one array representation, so this is the same value as the
-- raw control): the declared element projection must admit it and land the
-- element so ordinary DEAL reads observe it.
function M.nativeStringArray()
  return { "ok" }
end

-- Returns a declared string[][] whose nested element is a valid array: the
-- element projection must recurse through the nested position.
function M.nativeNestedStringArray()
  return { { "ok" } }
end

-- Returns a declared string[][] whose two elements are the same inner host
-- array: the projection maps the one raw host table to one DEAL array, so a
-- DEAL identity comparison of the two elements is true and a mutation
-- through either alias is observed through the other.
function M.nativeSharedNestedStringArray()
  local shared = { "ok" }
  return { shared, shared }
end

return M
