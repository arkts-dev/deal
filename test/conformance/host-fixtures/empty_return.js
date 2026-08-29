"use strict";

// Host fixture implementation for the host-empty-return-bad conformance
// test: zero results (undefined) for the declared int return — the
// wrapper's presence rule must raise E8010 on every call path,
// including the discard path.
module.exports = {
  ping: function () {
  },
};
