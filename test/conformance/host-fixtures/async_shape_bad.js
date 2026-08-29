"use strict";

// Host fixture implementation for the host-async-shape-bad conformance
// test: the declared export is an async function, so the host must
// return a backend async operation (a thenable); the plain number below
// must be rejected at the call site with E8010 (async-operation shape),
// before the await-site completion check ever runs.
module.exports = {
  fetchValue: function () {
    return 42;
  },
};
