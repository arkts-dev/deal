-- Host fixture implementation for the host-rest-ok/host-rest-bad
-- conformance tests: rest args arrive as plain Lua varargs.

return {
  join = function(sep, ...)
    local parts = { ... }
    return table.concat(parts, sep)
  end,
}
