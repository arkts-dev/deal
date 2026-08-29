"use strict";

// Host fixture implementation for the host-async-ok conformance test:
// a real backend async operation (a thenable) whose completion value
// satisfies the declared async return type string.
module.exports = {
  fetchValue: function () {
    return Promise.resolve("fetched");
  },
};
