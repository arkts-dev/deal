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
--- IEEE sqrt preserves NaN, positive infinity and signed zero.
mathlib.sqrt = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  return math.sqrt(x)
end)

return mathlib
