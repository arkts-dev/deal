-- DEAL Standard Library: std/math
-- Provides math functions with type checks.

local __rt = require("deal.runtime")

local mathlib = {}

--- Returns the absolute value.
function mathlib.abs(x)
  __rt.check_int(x)
  return math.abs(x)
end

--- Returns the minimum of two integers.
function mathlib.min(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a < b then return a else return b end
end

--- Returns the maximum of two integers.
function mathlib.max(a, b)
  __rt.check_int(a)
  __rt.check_int(b)
  if a > b then return a else return b end
end

--- Returns the floor of a number.
function mathlib.floor(x)
  __rt.check_number(x)
  return __rt.check_int(math.floor(x))
end

--- Returns the ceiling of a number.
function mathlib.ceil(x)
  __rt.check_number(x)
  return __rt.check_int(math.ceil(x))
end

--- Returns the absolute value of a number.
function mathlib.absf(x)
  __rt.check_number(x)
  return math.abs(x)
end

--- Returns the square root.
function mathlib.sqrt(x)
  __rt.check_number(x)
  return math.sqrt(x)
end

--- Returns x raised to the power y.
function mathlib.pow(x, y)
  __rt.check_number(x)
  __rt.check_number(y)
  return x ^ y
end

return mathlib
