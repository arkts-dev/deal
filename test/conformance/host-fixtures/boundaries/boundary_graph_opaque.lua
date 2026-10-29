local rt=require("deal.runtime");local saved;return {corrupt=function(r)saved=r;r.bad=coroutine.create(function()end) end,repair=function()saved.bad=nil end}
