"use strict";

// Host fixture implementation for the host-export-presence conformance
// test (host-module-abi D6). The declared class export carries the
// canonical externals identity descriptor (@$external/host/presence/Config) and a
// <C>_defaults table — runtime construction through the synthesized
// class symbol depends on both. The preserved defaults-map seam
// (host-module-abi D2, ISSUE-0331): the loader passes the defaults
// table through verbatim, and this host does NOT mark its absent
// optional fields with $rt.MISSING — so a provided declared optional
// absent from Config_defaults raises E8007 at construction (the
// host-class-extra-field fixture).
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
