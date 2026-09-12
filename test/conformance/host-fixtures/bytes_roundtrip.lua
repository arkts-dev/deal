local rt = require("deal.runtime")
local calls = 0
local shared = rt.bytes_new(2, nil, nil, nil)
return {
  echoBytes = function(b) calls = calls + 1 return b end,
  nullableBytes = function(b) calls = calls + 1 return b end,
  makeBytes = function(n) calls = calls + 1 return rt.bytes_new(n, nil, nil, nil) end,
  sharedBytes = function() return shared end,
  readByte = function(b) calls = calls + 1 return rt.bytes_get(b, 0, nil, nil, nil) end,
  callCount = function() return calls end,
  badBytesReturn = function() calls = calls + 1 return "not-bytes" end
}
