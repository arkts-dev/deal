-- Host fixture implementation for the host-async-shape-bad conformance
-- test: the raw function returns a plain number (42), not a backend async
-- operation. The __rt.load_host wrapper must raise E8010 ("host async
-- function must return an async operation") at the call site — before the
-- await machinery ever sees a value.

return {
  fetchValue = function()
    return 42
  end,
}
