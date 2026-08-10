-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value of an int.
mathlib.absInt = __rt.function_("(int)->int", function(x)
  return __rt.check_int(math.abs(__rt.check_int(x)))
end)

--- Returns the absolute value of a number.
mathlib.absNumber = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  return math.abs(x)
end)

--- Returns the minimum of two ints.
mathlib.minInt = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  return __rt.check_int(math.min(a, b))
end)

--- Returns the maximum of two ints.
mathlib.maxInt = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  return __rt.check_int(math.max(a, b))
end)

--- Returns the floor of a number as a number (not int).
mathlib.floor = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  return math.floor(x)
end)

--- Returns the ceiling of a number as a number (not int).
mathlib.ceil = __rt.function_("(number)->number", function(x)
  __rt.check_number(x)
  return math.ceil(x)
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
