"use strict";

// Host fixture implementation for the ISSUE-0340 default-plan fixtures
// (Node lane). nextValue() increments a module counter and returns the
// count * 10; valueCount() reads it — the Node host-slice mirror of the
// Lua host fixture's mutable state.
let n = 0;

module.exports = {
  nextValue: function () {
    n = n + 1;
    return n * 10;
  },
  valueCount: function () {
    return n;
  },
};
