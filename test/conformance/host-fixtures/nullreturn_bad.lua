-- Host fixture implementation for the host-null-return-bad conformance
-- test: a junk return for the sync ->null declared return.

return {
  ping = function()
    return "junk"
  end,
}
