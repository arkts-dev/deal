-- Host fixture implementation for the host-boundary-apply-function
-- conformance test. The function-typed parameter arrives as an adapted
-- plain Lua function (host-module-abi D3 case 4).

return {
  apply = function(f, v)
    return f(v) + 100
  end,
}
