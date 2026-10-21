"use strict";

// Host fixture implementation for the three-lane @jsonable plain-table
// content conformance test (ISSUE-0868). Every declared table-typed return
// is the runtime table Map carrying the JSON-shaped data the class helper's
// toJson walk accepts: an object, an object with a nested object and an
// array member, and a self-referential table for the closed cycle arm. The
// closed arms' returns carry a non-finite numeric leaf and an invalid
// (unpaired-surrogate) object key, the two content shapes the finite
// acyclic JSON domain excludes.

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
};
