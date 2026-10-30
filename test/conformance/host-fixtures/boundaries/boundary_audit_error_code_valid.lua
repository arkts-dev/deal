local saved;return {edit=function(e)saved=e;e.code="changed 🌍" end,repair=function()saved.code="repaired" end}
