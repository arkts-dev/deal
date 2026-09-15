-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value of an int. The trailing span triplet
-- carries the call site (ISSUE-0598): the input check and the signed-
-- int32 result gate both report it.
mathlib.absInt = __rt.function_("(int)->int", function(x, file, line, column)
  return __rt.check_int(math.abs(__rt.check_int(x, file, line, column)), file, line, column)
end)

--- Returns the absolute value of a number.
mathlib.absNumber = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  return math.abs(x)
end)

--- Returns the minimum of two ints.
mathlib.minInt = __rt.function_("(int,int)->int", function(a, b, file, line, column)
  __rt.check_int(a, file, line, column)
  __rt.check_int(b, file, line, column)
  return __rt.check_int(math.min(a, b), file, line, column)
end)

--- Returns the maximum of two ints.
mathlib.maxInt = __rt.function_("(int,int)->int", function(a, b, file, line, column)
  __rt.check_int(a, file, line, column)
  __rt.check_int(b, file, line, column)
  return __rt.check_int(math.max(a, b), file, line, column)
end)

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
--- Rejects negative inputs.
mathlib.sqrt = __rt.function_("(number)->number", function(x, file, line, column)
  __rt.check_number(x, file, line, column)
  if x < 0 then
    error(__rt._err("E8001", "sqrt of negative number", file, line, column, "non-negative number", tostring(x)))
  end
  return math.sqrt(x)
end)

return mathlib
