"use strict";

// Host fixture implementation for the host-missing-export conformance
// test. The declared surface names "missing", which this implementation
// omits: the loader must raise E8011 at load time, before any exported
// function auto-invocation (R3).
module.exports = {
  ping: function () {
    return "pong";
  },
};
