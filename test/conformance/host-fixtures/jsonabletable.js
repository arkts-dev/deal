"use strict";

// Host Record-content JSON conformance fixture: ordinary objects, nested
// objects/arrays, a real contextual decode graph and a self-reference for
// the closed cycle arm. Non-finite leaves substitute null; lone UTF-16 units in
// object values and keys remain lossless JSON data.

const $rt = require("../deal/runtime");

module.exports = {
  makeTable: function () {
    return $rt.makeTable({ a: 1 });
  },

  makeNested: function () {
    return $rt.makeTable({
      inner: $rt.makeTable({ a: 1 }),
      xs: [10, 20],
    });
  },

  makeParsed: function () {
    // Actual contextual Record reconstruction retains binary64 leaves and
    // presence/order metadata through the host return.
    return $rt.jsonDecode("{\"v\":0.1,\"w\":1e17}", "record", "-", 0, 0);
  },

  makeCycle: function () {
    const t = $rt.makeTable({});
    t.set("self", t);
    return t;
  },

  makeInfinite: function () {
    return $rt.makeTable({ a: Infinity });
  },

  makeNotANumber: function () {
    return $rt.makeTable({ a: NaN });
  },

  makeNestedInfinite: function () {
    return $rt.makeTable({ inner: $rt.makeTable({ a: Infinity }) });
  },

  makeInfiniteElements: function () {
    return $rt.makeTable({ xs: [Infinity, 2] });
  },

  makeBadKey: function () {
    const t = $rt.makeTable({});
    t.set("\uD800", 1);
    return t;
  },

  makeNestedBadKey: function () {
    const inner = $rt.makeTable({});
    inner.set("\uD800", 1);
    return $rt.makeTable({ inner: inner });
  },

  makeBadString: function () {
    return $rt.makeTable({ a: "\uD800" });
  },
};
