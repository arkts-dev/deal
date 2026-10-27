local rt = require('deal.runtime')
return {
  echo = function(value) return value end,
  undefinedValue = function() return rt.__UNDEFINED end,
  nothing = function() end,
  maybeNumber = function(value) return value end,
  maybeString = function(value) return value end,
  maybeBoolean = function(value) return value end,
  mutate = function(value) value[1] = 9; return value end,
  fresh = function() return {4} end,
  mutateClass = function(value) value.n = 8; return value end,
  classNumber = function(value) return value.n end,
  callback = function(value) value() end,
  asyncUnknown = function() return rt.async_start(function() return rt.__UNDEFINED end) end,
  asyncUndefined = function() return rt.async_start(function() return rt.__UNDEFINED end) end,
  asyncVoid = function() return rt.async_start(function() return rt.__UNDEFINED end) end,
  invalidNested = function() return {child = {bad = string.char(0xed,0xa0,0x80)}} end,
  opaque = function() return setmetatable({}, {__index = function() error('host hook invoked') end}) end,
  graph = function() local child = {}; local value = {a = child, b = child}; value.self = value; return value end
}
