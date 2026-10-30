local saved;return {edit=function(e)saved=e;e.message=nil end,repair=function()saved.message="repaired" end}
