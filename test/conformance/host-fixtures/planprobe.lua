-- Host fixture implementation for the ISSUE-0340 default-plan fixtures.
-- Holds mutable host-side state (the invocation counter): nextValue()
-- increments the counter and returns the count * 10; valueCount() reads
-- it. A DEAL class default calling nextValue() therefore observes every
-- evaluator invocation from the host side.

local n = 0

return {
  nextValue = function()
    n = n + 1
    return n * 10
  end,
  valueCount = function()
    return n
  end,
}
