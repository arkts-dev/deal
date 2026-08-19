-- Host fixture implementation for the host-rest-ok/host-rest-bad
-- conformance tests: v1.2 fixed array parameter.

return {
  join = function(sep, parts)
    return table.concat(parts, sep)
  end,
}
