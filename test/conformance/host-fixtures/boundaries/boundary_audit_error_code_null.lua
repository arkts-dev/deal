local saved;return {edit=function(e)saved=e;e.code=nil end,repair=function()saved.code="repaired" end}
