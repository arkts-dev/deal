"use strict";

// Host fixture implementation for the host-null-return-ok conformance
// test. JS null is the DEAL null sentinel for a sync function declared
// ->null — the wrapper's sync-null check accepts it and rejects any
// non-sentinel value (plain undefined included).
module.exports = {
  ping: function () {
    return null;
  },
};
