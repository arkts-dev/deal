"use strict";

// Host fixture implementation for the host-nullable-function-param and
// host-nullable-function-param-bad conformance tests. The declared
// parameter is ?((x: int) => int): the wrapper accepts the DEAL null
// (JS null here) passed through unadapted and matching-sig DEAL
// functions (delivered as their { $kind: "function", $sig, $f }
// wrappers, invoked through .$f), and raises E8010 for anything else.
module.exports = {
  register: function (cb) {
    if (cb === null || cb === undefined) {
      return 0;
    }
    return cb.$f(41);
  },
};
