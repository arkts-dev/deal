-- Host fixture implementation for the host-origin function-value binding
-- subject: two same-signature exports (inc/dec) and a call trace naming the
-- export each invocation reached, so a stale dispatch is observable.

local trace = ""

return {
  inc = function(x)
    trace = trace .. "I"
    return x + 1
  end,
  dec = function(x)
    trace = trace .. "D"
    return x - 1
  end,
  trace = function()
    return trace
  end,
}
