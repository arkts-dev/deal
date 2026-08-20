-- Host-module fixture (host-module-abi D6): a host export returning a string
-- with a malformed UTF-8 byte sequence.  DEAL v1.2 string boundary rules
-- require boundary checks to reject invalid encodings.

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

return M
