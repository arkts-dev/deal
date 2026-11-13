local saved;return {edit=function(e)saved=e;e.name="changed 🌍" end,repair=function()saved.name="repaired" end}
