"use strict";

// Host fixture implementation for the host-prewrapped-bad conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JS host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): ping returns junk
// for the declared ->null return, so the wrapper's sync-null check must
// raise E8010, including the discard call path.
module.exports = {
  ping: function () {
    return "junk";
  },
};
