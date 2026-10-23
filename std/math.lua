-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value of an int. The trailing span triplet
-- carries the call site (ISSUE-0598): the input check and the signed-
-- int32 result gate both report it.

--- Returns the absolute value of a number.
mathlib.absNumber = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  return math.abs(x)
end)

--- Returns the minimum of two ints.

--- Returns the maximum of two ints.

--- Returns the floor of a number as a number (not int).
mathlib.floor = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  return math.floor(x)
end)

--- Returns the ceiling of a number as a number (not int).
mathlib.ceil = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  return math.ceil(x)
end)

--- Returns the square root of a number.
--- Rejects negative inputs: the closed SQRT_NEGATIVE arm (E8001 "sqrt of
-- negative number") with no expected field and the negative operand's
-- canonical hex-float spelling as the actual — the tuple the emitted
-- LuaJIT chunk, the JVM runtime and the shared oracle publish.
mathlib.sqrt = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  if x < 0 then
    error(__rt._err("E8001", "sqrt of negative number", file, line, column, nil, __rt.number_hex(x)))
  end
  return math.sqrt(x)
end)

return mathlib
