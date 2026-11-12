local saved
local keys = rawget(_G, '__dealCarrierKeys')
return {
  keep = function(value) saved = value end,
  replace = function(index,value) saved[index+1] = value end,
  append = function(value) local n=saved[keys.n]+1; saved[n]=value; saved[keys.n]=n end,
  size = function() return saved[keys.n] end
}
