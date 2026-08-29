"use strict";

// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings violate DEAL v1.2 string encoding rules. JS strings
// are UTF-16, so lone surrogate code units are the JS analog of the Lua
// host's malformed UTF-8 bytes — the declared (string) boundary must
// reject both returns with E8010.
module.exports = {
  badString: function () {
    return "a\uD800b";
  },

  surrogateString: function () {
    return "\uD800";
  },
};
