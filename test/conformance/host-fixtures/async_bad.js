"use strict";

// Host fixture implementation for the host-async-bad conformance test:
// the returned value is a valid backend async operation (a thenable),
// but its completion value 42 violates the declared async return type
// string — the await-site completion check must raise E8001.
module.exports = {
  fetchValue: function () {
    return Promise.resolve(42);
  },
};
