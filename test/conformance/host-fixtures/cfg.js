"use strict";

// Host fixture implementation for the host-class-export conformance
// test (host-module-abi D6 + runtime-class-identity D1-D2). Each
// declared class export carries its canonical externals identity META and
// a <C>_defaults table (construction depends on both). The preserved
// defaults-map seam (host-module-abi D2, ISSUE-0331 gate closure): the
// loader passes the defaults table through verbatim, so the host owns
// which absent optional fields its defaults table marks — the
// $rt.MISSING marks below mirror the Lua triplet's __MISSING marks
// (cfg.lua), and construction overlays provided optional fields over
// the marked entries exactly like the reference's class_.

const $rt = require("../deal/runtime");

module.exports = {
  Endpoint: {
    $kind: "class",
    $classname: "@$external/host/cfg/Endpoint",
  },

  Endpoint_defaults: {
    path: "/",
  },

  ServerConfig: {
    $kind: "class",
    $classname: "@$external/host/cfg/ServerConfig",
  },

  ServerConfig_defaults: {
    port: 8080,
    endpoint: $rt.MISSING,
    tags: $rt.MISSING,
    note: $rt.MISSING,
  },

  describe: function (s) {
    return s.endpoint.path + ":" + s.port;
  },
};
