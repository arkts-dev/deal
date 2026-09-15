local rt = require("deal.runtime")
local nextValue = 0
return {
  intValue = function() return 17 end,
  stringValue = function() return "host" end,
  nullableString = function(flag) if flag then return "host" end return rt.__NULL end,
  nullValue = function() return rt.__NULL end,
  nextValue = function() nextValue = nextValue + 1 return nextValue end,
  echoInt = function(value) return value end,
  echoNumber = function(value) return value end,
  echoBoolean = function(value) return value end,
  echoString = function(value) return value end,
  nullableInt = function(value) return value end,
  extraExport = function() return "ignored" end,
  apply = function(value, callback) return callback(value) end
}
