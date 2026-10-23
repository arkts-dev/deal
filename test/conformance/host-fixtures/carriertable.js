"use strict";

// Host fixture implementation for the three-lane host-carrier
// table-member conformance test. The declared classes export their
// canonical host identities and defaults; a host-provided function value
// is the documented wrapper ($kind/$sig/$f), a class value is the class
// object with its declared fields, and the table-typed return carries a
// class member and a function member that DEAL reads at their declared
// types. The table-typed parameter is inspected member by member: the
// DEAL class member must arrive as a class value with top-level fields,
// the alias must stay one object, and the DEAL function member must be
// invocable with its declared result.

const $rt = require("../deal/runtime");

const WIDGET = "@$external/host.carriertable/Widget";
const BOARD = "@$external/host.carriertable/Board";

function step(x) {
  return 42;
}

function functionValue() {
  return { $kind: "function", $sig: "(number)->number", $f: step };
}

let LAST_TABLE = null;
let LAST_WIDGET = null;
let LAST_ACTION = null;

function widget(label, weight) {
  return {
    $kind: "class",
    $classname: WIDGET,
    label: label,
    weight: weight,
    action: functionValue(),
  };
}

module.exports = {
  Widget: { $kind: "class", $classname: WIDGET },
  Widget_defaults: { label: "", weight: 0, action: $rt.MISSING },

  Board: { $kind: "class", $classname: BOARD },
  Board_defaults: { title: "", widgets: $rt.MISSING, meta: $rt.MISSING },

  describeAsync: function (w, g) {
    let gText = "none";
    if (g !== null && typeof g === "object" && g.$kind === "function") {
      try {
        gText = "true:" + String(g.$f(41));
      } catch (error) {
        gText = "false:" + String(error && error.code);
      }
    }
    return Promise.resolve("W|" + String(w.label) + "|" + String(w.weight) +
      "|" + gText);
  },

  makeTable: function () {
    LAST_WIDGET = widget("made", 9);
    LAST_ACTION = functionValue();
    LAST_TABLE = new Map();
    LAST_TABLE.set("widget", LAST_WIDGET);
    LAST_TABLE.set("action", LAST_ACTION);
    return LAST_TABLE;
  },

  // The host object the copied crossing projects: every member is still
  // the exact object the host put there (no replacement by a projected
  // value and no in-place rewrite of a member), the class member stays the
  // host class instance with its declared identity and fields, and the
  // action member stays the host function wrapper.
  tableIntact: function () {
    if (LAST_TABLE === null) {
      return false;
    }
    const member = LAST_TABLE.get("widget");
    const action = LAST_TABLE.get("action");
    return member === LAST_WIDGET && action === LAST_ACTION &&
      member !== null && typeof member === "object" &&
      member.$kind === "class" && member.$classname === WIDGET &&
      action !== null && typeof action === "object" &&
      action.$kind === "function";
  },

  describeTable: function (t) {
    const point = t.get("point");
    let classed = "false";
    let fields = "-";
    if (point !== null && typeof point === "object" &&
        point.$kind === "class") {
      const name = String(point.$classname);
      classed = String(name.endsWith("/Point"));
      fields = String(point.x) + "," + String(point.y);
    }
    const alias = String(t.get("point") === t.get("twin"));
    let stepText = "none";
    const member = t.get("step");
    if (member !== null && typeof member === "object" &&
        member.$kind === "function") {
      try {
        stepText = "true:" + String(member.$f(41));
      } catch (error) {
        stepText = "false:" + String(error && error.code);
      }
    }
    return "T|" + classed + "|" + fields + "|" + alias + "|" + stepText;
  },

  makeBoard: function () {
    const meta = new Map();
    meta.set("widget", widget("w1", 5));
    meta.set("action", functionValue());
    return {
      $kind: "class",
      $classname: BOARD,
      title: "board",
      widgets: [widget("w0", 9)],
      meta: meta,
    };
  },
};
