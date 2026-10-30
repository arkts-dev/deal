local saved;return {edit=function(e)saved=e;e.code=string.char(237,160,128) end,repair=function()saved.code="repaired" end}
