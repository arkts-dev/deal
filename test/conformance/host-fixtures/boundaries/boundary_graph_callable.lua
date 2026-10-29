local rt=require("deal.runtime");local saved;return {corrupt=function(r)saved=r;r.bad=rt.function_("()->number",nil) end,repair=function()saved.bad=nil end}
