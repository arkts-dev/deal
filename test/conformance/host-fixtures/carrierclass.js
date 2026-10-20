"use strict";

// Host fixture implementation for the three-lane host-carrier class-value
// conformance test (the ISSUE-0848 acceptance shapes). Each declared class
// exports its canonical host identity ($kind/$classname) and a
// <C>_defaults table (construction reads both; the optional fields default
// to the runtime's absent marker). A class value crosses to the host as
// its declared top-level fields with the $kind/$classname tags, a declared
// function parameter is the DEAL function wrapper invoked through .$f,
// and the asynchronous exports return real promises whose completions are
// the declared class values. Every produced instance carries value = 42 so
// each assertion proves the declared value survived the crossing.

const $rt = require("../deal/runtime");

const VALUE = "@$external/host.carrierclass/Config";
const HOLDER = "@$external/host.carrierclass/Holder";

function config(value) {
  return { $kind: "class", $classname: VALUE, value: value };
}

function holder(primary) {
  return {
    $kind: "class",
    $classname: HOLDER,
    label: "holder",
    primary: primary,
    optional: null,
    items: [config(42)],
  };
}

module.exports = {
  Config: { $kind: "class", $classname: VALUE },
  Config_defaults: { value: 0 },

  Holder: { $kind: "class", $classname: HOLDER },
  Holder_defaults: {
    label: "",
    primary: $rt.MISSING,
    optional: $rt.MISSING,
    maybe: $rt.MISSING,
    items: $rt.MISSING,
  },

  describeAsync: function (c) {
    return Promise.resolve(c.value);
  },

  makeAsync: function () {
    return Promise.resolve(config(42));
  },

  makeSync: function () {
    return config(42);
  },

  makeAll: function () {
    const shared = config(42);
    return [shared, shared];
  },

  maybeMake: function (flag) {
    return flag ? config(42) : null;
  },

  describeOrNull: function (c) {
    return c === null || c === undefined ? -1 : c.value;
  },

  maybeMakeAsync: function (flag) {
    return Promise.resolve(module.exports.maybeMake(flag));
  },

  makeHolder: function () {
    return holder(config(42));
  },

  consume: function (g) {
    if (g === null || typeof g !== "object" || g.$kind !== "function") {
      return -2;
    }
    try {
      const result = g.$f(config(42));
      return result === null || typeof result !== "object" ||
        result.$kind !== "class" ? -3 : result.value;
    } catch (error) {
      return -4;
    }
  },

  makeWrongKind: function () {
    return 42;
  },

  makeNullClass: function () {
    return null;
  },

  makeWrongElement: function () {
    return [config(42), "junk"];
  },

  makeSparse: function () {
    const sparse = [];
    sparse[0] = config(42);
    sparse[2] = config(42);
    return sparse;
  },

  makeMap: function () {
    return { alpha: config(42) };
  },
};
