"use strict";

// Host fixture implementation for the host-bad-return conformance test:
// a junk return value for the declared int return type — the wrapper
// must raise E8010 at the call site.
module.exports = {
  getNumber: function () {
    return "not a number";
  },
};
