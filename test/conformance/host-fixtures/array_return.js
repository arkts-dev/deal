"use strict";

// Host fixture implementation for the host-array-return-ok conformance
// test: a plain JS Array of strings satisfies the declared string[]
// return (the boundary wrapper checks the carrier and its elements).
module.exports = {
  split: function (s) {
    return ["a", "b", "c"];
  },
};
