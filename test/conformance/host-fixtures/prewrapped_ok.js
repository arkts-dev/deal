"use strict";

// Host fixture implementation for the host-prewrapped-ok conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JS host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): greet returns the
// declared string and ping's sync ->null return is the DEAL null, both
// enforced through the declared-descriptor wrapper.
module.exports = {
  greet: function (name) {
    return "hello " + name;
  },

  ping: function () {
    return null;
  },
};
