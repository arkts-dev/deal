"use strict";

// Host fixture implementation for the host-null-return-bad conformance
// test: a junk return for the sync ->null declared return — the
// wrapper's sync-null check must raise E8010, including on the discard
// call path.
module.exports = {
  ping: function () {
    return "junk";
  },
};
