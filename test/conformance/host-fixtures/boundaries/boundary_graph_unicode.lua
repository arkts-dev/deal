local r;return {corrupt=function(v)r=v;r.bad=string.char(237,160,128) end,repair=function()r.bad=nil end}
