"use strict";

// Host fixture implementation for the three-lane host-carrier async-class
// conformance test. Each declared class exports its canonical host
// identity ($kind/$classname) and a <C>_defaults table (construction reads
// both; the optional fields default to the runtime's absent marker). A
// class value crosses to the host as its declared top-level fields with
// the $kind/$classname tags, a declared function parameter is the DEAL
// function wrapper invoked through .$f, and the async exports return real
// promises whose completions are the declared class/array values.

const $rt = require("../deal/runtime");

const EP = "@$external/host.carrierasync/Endpoint";
const SC = "@$external/host.carrierasync/ServerConfig";
const FARM = "@$external/host.carrierasync/Farm";

function endpoint(path) {
  return { $kind: "class", $classname: EP, path: path };
}

function config(port, path) {
  return {
    $kind: "class",
    $classname: SC,
    port: port,
    endpoint: endpoint(path),
    parts: ["x", "y"],
    note: null,
  };
}

function arrayText(value) {
  if (Array.isArray(value)) {
    return value.length + ":" + String(value[0]);
  }
  return "absent";
}

function textOr(value) {
  if (value === null) {
    return "null";
  }
  if (value === undefined) {
    return "absent";
  }
  return String(value);
}

function describe(s, g) {
  let gText = "none";
  if (g !== null && typeof g === "object" && g.$kind === "function") {
    try {
      gText = "true:" + String(g.$f(41));
    } catch (error) {
      gText = "false:" + String(error && error.code);
    }
  }
  return "SC|" + String(s.port) + "|" + String(s.endpoint.path) +
    "|" + arrayText(s.parts) + "|" + arrayText(s.tags) +
    "|" + textOr(s.note) + "|" + gText;
}

module.exports = {
  Endpoint: { $kind: "class", $classname: EP },
  Endpoint_defaults: { path: "/" },

  ServerConfig: { $kind: "class", $classname: SC },
  ServerConfig_defaults: {
    port: 0,
    endpoint: $rt.MISSING,
    parts: $rt.MISSING,
    tags: $rt.MISSING,
    note: $rt.MISSING,
  },

  Farm: { $kind: "class", $classname: FARM },
  Farm_defaults: {
    name: "",
    servers: $rt.MISSING,
    primary: $rt.MISSING,
  },

  describeAsync: function (s, g) {
    return Promise.resolve(describe(s, g));
  },

  make: function () {
    return Promise.resolve({
      $kind: "class",
      $classname: SC,
      port: 4242,
      endpoint: endpoint("/made"),
      parts: ["a", "b"],
      note: null,
    });
  },

  makeAll: function () {
    const shared = config(11, "/one");
    return [shared, shared];
  },

  makeFarm: function () {
    const shared = config(12, "/two");
    return {
      $kind: "class",
      $classname: FARM,
      name: "farm",
      servers: [shared],
      primary: endpoint("/p"),
    };
  },
};
