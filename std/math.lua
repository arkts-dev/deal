-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value.
mathlib.abs = __rt.function_("(int)->int", function(x)
  __rt.check_int(x)
  return math.abs(x)
end)

--- Returns the minimum of two integers.
mathlib.min = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a < b then return a else return b end
end)

--- Returns the maximum of two integers.
mathlib.max = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a > b then return a else return b end
end)

--- Returns the floor of a number.
mathlib.floor = __rt.function_("(number)->int", function(x)
  __rt.check_number(x)
  return __rt.check_int(math.floor(x))
end)

--- Returns the ceiling of a number.
mathlib.ceil = __rt.function_("(number)->int", function(x)
  __rt.check_number(x)
  return __rt.check_int(math.ceil(x))
end)

--- Returns the absolute value of a number.
mathlib.absf = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  return math.abs(x)
end)

--- Returns the square root.
mathlib.sqrt = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  return math.sqrt(x)
end)

--- Returns x raised to the power y.
mathlib.pow = __rt.function_("(number,number)->number", function(x, y)
  __rt.check_number(x)
  __rt.check_number(y)
  return x ^ y
end)

return mathlib
