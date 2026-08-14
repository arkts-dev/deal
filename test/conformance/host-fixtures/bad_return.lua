-- Host fixture implementation for the host-bad-return conformance test:
-- a junk return value for the declared int return type.

return {
  getNumber = function()
    return "not a number"
  end,
}
