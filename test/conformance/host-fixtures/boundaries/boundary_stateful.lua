local saved
return {
  keep = function(value) saved = value end,
  echo = function(value) return value end,
  mutate = function(value) saved[1] = value end,
  corrupt = function() saved[1] = "bad" end,
  callback = function(f, value) return f(value) end,
}
