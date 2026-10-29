local r;return {corrupt=function(v)r=v;r.bad=3 end,repair=function()r.bad=nil end}
