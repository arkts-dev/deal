local saved;return {edit=function(e)saved=e;e.message="changed" end,read=function(e)if e~=saved then return "LOST_IDENTITY" end;return e.message end}
