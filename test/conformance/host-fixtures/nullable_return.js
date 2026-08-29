"use strict";

// Host fixture implementation for the host-nullable-return-ok/bad
// conformance tests. JS null is the null result; an int return
// exercises the wrong-representation E8010 path.
module.exports = {
  find: function (s) {
    if (s === "__NULL__") {
      return null;
    }
    if (s === "__BAD__") {
      return 42;
    }
    return s;
  },
};
