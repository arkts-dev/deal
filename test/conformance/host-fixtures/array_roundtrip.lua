local function make(n)
  local a = {}
  for i = 1, n do a[i] = 0 end
  return a
end
local calls = 0
local rt = require("deal.runtime")
local K = rt.__keys
local shared = {[K.a] = true, [K.n] = 2, 0, 0}
return {
  echoArray = function(b) calls = calls + 1 return b end,
  nullableArray = function(b) calls = calls + 1 return b end,
  makeArray = function(n) calls = calls + 1 return make(n) end,
  sharedArray = function() return shared end,
  readElement = function(b) calls = calls + 1 return b[1] end,
  callCount = function() return calls end,
  badArrayReturn = function() calls = calls + 1 return "not-array" end
}
