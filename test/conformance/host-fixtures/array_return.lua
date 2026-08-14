-- Host fixture implementation for the host-array-return-ok conformance
-- test: a plain Lua table of strings satisfies the string[] declared
-- return (the wrapper checks elements via check_array).

return {
  split = function(s)
    return { "a", "b", "c" }
  end,
}
