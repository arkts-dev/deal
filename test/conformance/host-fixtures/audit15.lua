local rt = require('deal.runtime')
local K = rt.__keys
local identity = '@$external/host.audit15/C'
local retained, growing, callback, unknown
return {
  C = {[K.kind]='class',[K.classname]=identity},
  badClass = function(kind)
    local value={[K.kind]='class',[K.classname]=identity}
    if kind==0 then value.x=rt.__NULL elseif kind==1 then value.x=string.char(0xed,0xa0,0x80) elseif kind==3 then value.x=1 end
    return value
  end,
  nullish = function() return rt.__UNDEFINED end,
  echoArray = function(value) return value end,
  replace = function(value) value[1]=2 end,
  numbers = function(value) return value end,
  booleans = function(value) return value end,
  strings = function(value) return value end,
  keep = function(value) retained=value end,
  seesUndefined = function() return retained[1]==rt.__UNDEFINED end,
  fresh = function() growing={1};return growing end,
  grow = function() growing[2]=2 end,
  conflict = function() return {[K.a]=true,[K.n]=1,[K.elementDescriptor]='number',1} end,
  callbacks = function(a,b) local same=a==b and (callback==nil or callback==a);callback=a;return same end,
  echoFunction = function(value) return value end,
  nativeFailure = function() error('native',0) end,
  nativeCallable = function() return rt.function_('()->void',function() error('callable',0) end) end,
  callbackFailure = function(value) value() end,
  asyncFailure = function() return rt.async_start(function() error('native async',0) end) end,
  asyncCallback = function(value) return rt.async_start(function() value();return rt.__UNDEFINED end) end,
  echoError = function(value) error(value,0) end,
  keepUnknown = function(value) unknown=value end,
  corrupt = function(kind) if kind==0 then unknown[1]=string.char(0xed,0xa0,0x80) else unknown[1]=function() end end end,
  opaqueArray = function() return {setmetatable({},{__index=function() error('hook invoked') end})} end
}
