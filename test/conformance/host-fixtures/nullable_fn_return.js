"use strict";

// Host fixture implementation for the host-nullable-function-return
// conformance tests. The declared return descriptor is ?((x: int) =>
// int): JS null is the null result, and a raw JS function value fails
// the function-typed return check — function-typed return wrapping is
// excluded (assumption c), so the wrapper must raise E8010 for the
// "bad" path.
module.exports = {
  getCallback: function (mode) {
    if (mode === "bad") {
      return function (x) {
        return x;
      };
    }
    return null;
  },
};
