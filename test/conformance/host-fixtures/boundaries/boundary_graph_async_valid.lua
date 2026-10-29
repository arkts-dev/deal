local rt=require("deal.runtime");return {mutate=function(r)return rt.async_start(function()coroutine.yield(rt.async_create(function()return rt.__UNDEFINED end));r.bad=3;return rt.__UNDEFINED end)end}
