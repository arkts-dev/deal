-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value of a number (or integer, which is a subtype).
--
-- The single runtime implementation serves both the (int)->int and
-- (number)->number uses declared in the type system:
--   * When the input is a valid int, both input and output are validated with
--     check_int (rejecting NaN, Infinity, non-integer values, and safe-range
--     violations).
--   * When the input is a number but not an int, only check_number is used
--     (accepts NaN and Infinity) and the output is returned unchecked.
mathlib.abs = __rt.function_("(number)->number", function(x)
  -- Try int validation first: if the input passes check_int it is a safe
  -- integer and we should apply int-output validation as well.
  local intOk, intVal = pcall(__rt.check_int, x)
  if intOk then
    -- Input is a valid int; apply int-output validation.
    return __rt.check_int(math.abs(intVal))
  end
  -- Not an int: validate as number (accepts NaN, Infinity) and return.
  __rt.check_number(x)
  return math.abs(x)
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
  return __rt.check_int(math.max(a, b))
end)

--- Returns the minimum of two integers.
mathlib.min = __rt.function_("(int,int)->int", function(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  return __rt.check_int(math.min(a, b))
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
