local saved;return {edit=function(e)saved=e;e.name=nil end,repair=function()saved.name="repaired" end}
