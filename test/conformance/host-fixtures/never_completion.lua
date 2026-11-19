local rt = require('deal.runtime')
return {bad = function() return 42 end, nothing = function() end,
  badAsync = function() return rt.async_start(function() return 42 end) end}
