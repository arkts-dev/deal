local saved;return {edit=function(e)saved=e;e.message="changed 🌍" end,repair=function()saved.message="repaired" end}
