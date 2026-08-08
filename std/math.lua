-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value of an integer.
-- Input and output are checked as int.
mathlib.abs = __rt.function_("(int)->int", function(x)
  __rt.check_int(x)
  return __rt.check_int(math.abs(x))
end)

--- Returns the ceiling of a number as an integer.
mathlib.ceil = __rt.function_("(number)->int", function(x)
  __rt.check_number(x)
  return __rt.check_int(math.ceil(x))
end)

--- Returns the floor of a number as an integer.
mathlib.floor = __rt.function_("(number)->int", function(x)
  __rt.check_number(x)
  return __rt.check_int(math.floor(x))
end)

--- Returns the maximum of two integers.
mathlib.max = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a > b then return a else return b end
end)

--- Returns the minimum of two integers.
mathlib.min = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a < b then return a else return b end
end)

--- Returns the square root of a number.
--- Rejects negative inputs.
mathlib.sqrt = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  if x < 0 then
    error(__rt._err("E8001", "sqrt of negative number", nil, nil, nil, "non-negative number", tostring(x)))
  end
  return math.sqrt(x)
end)

return mathlib
