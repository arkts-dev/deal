"use strict";

// Host fixture implementation for the host-boundary-apply-function
// conformance test. The function-typed parameter arrives as the DEAL
// wrapper ({ $kind: "function", $sig, $f }); the host invokes it
// through .$f (DEAL→host adaptation, js-v12-host-abi-completion D2/D6).
module.exports = {
  apply: function (f, v) {
    return f.$f(v) + 100;
  },
};
