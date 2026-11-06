local saved, damaged, callable, body, bridgeBody, signature, canonical, bridgeSignature
local rt = require('deal.runtime')
local keys = rawget(_G, '__dealCarrierKeys')
return {
  keep = function(value) saved = value end,
  corrupt = function() saved[saved[keys.n]] = "bad" end,
  repair = function() saved[saved[keys.n]] = 9 end,
  size = function() return saved[keys.n] end,
  echo = function(value) return value end,
  make = function() saved = rt.tag_array({[1]=4,[2]=9,[keys.n]=2}, "number"); return saved end,
  grow = function() local n=saved[keys.n]+1; saved[n]=11; saved[keys.n]=n end,
  damage = function(value, restore) if not restore then damaged=value end; damaged.x = restore and 9 or "bad" end,
  poison = function(value)
    callable=value; body=value[keys.fn]; bridgeBody=value.f
    signature=value[keys.sig]; canonical=value[keys.csig]; bridgeSignature=value.sig
    value[keys.fn]=false; value.f=false
  end,
  poisonSignature = function(value)
    callable=value; body=value[keys.fn]; bridgeBody=value.f
    signature=value[keys.sig]; canonical=value[keys.csig]; bridgeSignature=value.sig
    value[keys.sig]="garbage"; value[keys.csig]="garbage"; value.sig="garbage"
  end,
  restoreFunction = function()
    callable[keys.fn]=body; callable.f=bridgeBody
    callable[keys.sig]=signature; callable[keys.csig]=canonical; callable.sig=bridgeSignature
  end,
}
