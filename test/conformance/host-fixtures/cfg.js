"use strict";

// Host fixture implementation for the host-class-export conformance
// test (host-module-abi D6 + runtime-class-identity D1-D2). Each
// declared class export carries its module-qualified identity META and
// a <C>_defaults table (construction depends on both). Absent optional
// fields are marked MISSING by the loader from the declared field
// metadata, so the defaults tables carry only the defaulted values.
module.exports = {
  Endpoint: {
    $kind: "class",
    $classname: "@host.cfg/Endpoint",
  },

  Endpoint_defaults: {
    path: "/",
  },

  ServerConfig: {
    $kind: "class",
    $classname: "@host.cfg/ServerConfig",
  },

  ServerConfig_defaults: {
    port: 8080,
  },

  describe: function (s) {
    return s.endpoint.path + ":" + s.port;
  },
};
