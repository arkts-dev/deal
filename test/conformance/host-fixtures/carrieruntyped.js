"use strict";

// Host fixture implementation for the three-lane untyped-nested-carrier
// round-trip conformance test. The host reports the member kinds it
// observed, invokes the function member with the declared argument, and
// echoes the container back. The echoed container is a fresh table carrier
// holding the same member values, so the values the host observed are the
// values DEAL receives back; the class value's identity, its fields and
// the alias are the host-readable shape of the declared table mapping.

const $rt = require("../deal/runtime");

function copyTable(source) {
  const out = new Map();
  for (const key of source.keys()) {
    if (typeof key === "string") {
      out.set(key, source.get(key));
    }
  }
  return out;
}

function report(t) {
  const member = t.get("point");
  let kind = "false";
  let fields = "-";
  if (member !== null && typeof member === "object" &&
      member.$kind === "class") {
    const name = String(member.$classname);
    kind = String(name.endsWith("/Point"));
    fields = String(member.x) + "," + String(member.y);
  }
  const alias = String(t.get("point") === t.get("twin"));
  let stepText = "none";
  const step = t.get("step");
  if (step !== null && typeof step === "object" &&
      step.$kind === "function") {
    try {
      stepText = "true:" + String(step.$f(41));
    } catch (error) {
      stepText = "false:" + String(error && error.code);
    }
  }
  return kind + "|" + fields + "|" + alias + "|" + stepText;
}

module.exports = {
  roundTrip: function (t) {
    const observed = report(t);
    const out = copyTable(t);
    out.set("observed", observed);
    return out;
  },

  roundTripArray: function (items) {
    const out = [];
    for (let index = 0; index < items.length; index++) {
      out.push(copyTable(items[index]));
    }
    return out;
  },
};
