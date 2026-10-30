local saved;return {edit=function(e)saved=e;e.message=string.char(237,160,128) end,repair=function()saved.message="repaired" end}
