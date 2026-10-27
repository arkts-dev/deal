local rt = require("deal.runtime")
local K = rt.__keys
return {
  replaceSignature = function(holder, descriptor)
    local value = holder.value
    if type(value) ~= "table" then return false end
    -- The outbound wrapper captures the original DEAL carrier. Deliberately
    -- hostile test code reaches it, without adding a production mutation API.
    local carrier = value
    if type(value.f) == "function" then
      for i = 1, 100 do
        local name, captured = debug.getupvalue(value.f, i)
        if name == nil then break end
        if name == "carrier" then carrier = captured; break end
      end
    end
    carrier.sig = descriptor
    carrier[K.csig] = descriptor
    carrier[K.sig] = descriptor
    return true
  end,
}
