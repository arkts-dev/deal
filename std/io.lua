-- DEAL Standard Library: std/io
-- Provides file I/O operations: readText and writeText.

local __rt = require("deal.runtime")

local io_lib = {}

--- Reads the entire contents of a text file.
-- @param path string — the file path
-- @return string — the file contents
io_lib.readText = __rt.function_("(string)->string", function(path)
  __rt.check_string(path)
  local f, err = io.open(path, "r")
  if not f then
    error(__rt._err("E8001", "cannot read file '" .. path .. "': " .. (err or "unknown error"), nil, nil, nil, nil, nil))
  end
  local content = f:read("*a")
  f:close()
  if content == nil then
    error(__rt._err("E8001", "failed to read content from file '" .. path .. "'", nil, nil, nil, nil, nil))
  end
  return __rt.check_string(content)
end)

--- Writes text to a file, overwriting if it exists.
-- @param path string — the file path
-- @param text string — the content to write
-- @return null
io_lib.writeText = __rt.function_("(string,string)->null", function(path, text)
  __rt.check_string(path)
  __rt.check_string(text)
  local f, err = io.open(path, "w")
  if not f then
    error(__rt._err("E8001", "cannot write file '" .. path .. "': " .. (err or "unknown error"), nil, nil, nil, nil, nil))
  end
  f:write(text)
  f:close()
  return __rt.__NULL
end)

return io_lib
