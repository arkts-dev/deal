"use strict";

// Host fixture implementation for the host-export-presence conformance
// test (host-module-abi D6). The declared class export carries the
// canonical externals identity descriptor (@$external/host/presence/Config) and a
// <C>_defaults table — runtime construction through the synthesized
// class symbol depends on both. Absent optional fields are marked
// MISSING by the loader from the declared field metadata.
module.exports = {
  ping: function () {
    return "pong";
  },

  Config: {
    $kind: "class",
    $classname: "@$external/host/presence/Config",
  },

  Config_defaults: {
    port: 8080,
  },
};
