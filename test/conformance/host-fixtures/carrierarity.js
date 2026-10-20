"use strict";

// Host fixture implementation for the three-lane arity-extended
// adapter-over-async conformance test. The declared async export reads the
// class argument's declared field, invokes the function argument with the
// declared argument and returns a promise carrying the declared result.
// The extra target parameter of an arity-extended call is never forwarded,
// so it is not a parameter of this function.

module.exports = {
  Target: {
    $kind: "class",
    $classname: "@$external/host.carrierarity/Target",
  },

  Target_defaults: {
    name: "",
  },

  describeAsync: function (t, g) {
    let gText = "none";
    if (g !== null && typeof g === "object" && g.$kind === "function") {
      try {
        gText = "true:" + String(g.$f(41));
      } catch (error) {
        gText = "false:" + String(error && error.code);
      }
    }
    return Promise.resolve("T|" + String(t.name) + "|" + gText);
  },
};
