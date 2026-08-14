-- Host fixture implementation for the host-missing-export conformance
-- test. The declared surface names "missing", which this raw table omits:
-- __rt.load_host must raise E8011 at load time (missing declared export),
-- before any exported function auto-invocation.

return {
  ping = function()
    return "pong"
  end,
}
