"use strict";

// Host fixture implementation for the host-extra-export-ignored
// conformance test (host-module-abi D6). The implementation supplies
// more exports than the declared surface (ping): extra and helper are
// dropped structurally by the loader and the load must succeed (R4).
module.exports = {
  ping: function () {
    return "pong";
  },

  extra: 42,

  helper: function () {
    return "unreachable";
  },
};
