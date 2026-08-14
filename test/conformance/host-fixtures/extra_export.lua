-- Host fixture implementation for the host-extra-export-ignored
-- conformance test. Extra exports beyond the declared surface must be
-- ignored (R4): the loader drops them structurally and the load succeeds.

return {
  ping = function()
    return "pong"
  end,
  extra = 42,
  helper = function()
    return "unreachable"
  end,
}
