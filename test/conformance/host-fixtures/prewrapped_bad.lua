-- Host fixture implementation for the host-prewrapped-bad conformance
-- test: matching sig, junk return — enforcement must survive the re-wrap.

return {
  ping = {
    __kind = "function",
    sig = "()->null",
    f = function()
      return "junk"
    end,
  },
}
