local saved;return {edit=function(e)saved=e;e.name=string.char(237,160,128) end,repair=function()saved.name="repaired" end}
