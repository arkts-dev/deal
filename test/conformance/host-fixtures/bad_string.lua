-- Host-module fixture (host-module-abi D6): a host surface whose returned
-- strings exercise the DEAL v1.2 string carrier classes.  The declared
-- string boundary rejects the malformed and surrogate code point encodings
-- and admits the valid ones; the raw carrier decodes at scalar and array
-- element positions (including nested and nullable element positions).

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

return M
