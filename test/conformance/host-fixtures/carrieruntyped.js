"use strict";

// Host fixture implementation for the three-lane untyped-nested-carrier
// round-trip conformance test. The host reports the member kinds it
// observed (the DEAL class instance identity and top-level fields, the
// function wrapper and its invocation result, and the builtin Error
// instance identity with its code/message), and echoes the container back.
// The echoed container is a fresh table carrier holding the same member
// values, so the values the host observed are the values DEAL receives
// back. The array export reports each element's class member the same way
// and the observed alias identity between the elements.

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
  let errText = "none";
  const err = t.get("err");
  if (err !== null && typeof err === "object" && err.$kind === "class" &&
      err.$classname === "Error") {
    errText = "true:" + String(err.code) + ":" + String(err.message);
  }
  return kind + "|" + fields + "|" + alias + "|" + stepText + "|" + errText;
}

// One array element's class member as the host observes it: the declared
// class identity and the top-level declared fields. A member left in a
// DEAL-internal representation carries no host-readable class tag and no
// top-level field, so it reports "false|-" here.
function reportElement(item) {
  const member = item.get("point");
  if (member === null || typeof member !== "object" ||
      member.$kind !== "class") {
    return "false|-";
  }
  const name = String(member.$classname);
  return String(name.endsWith("/Point")) + "|" +
    String(member.x) + "," + String(member.y);
}

module.exports = {
  roundTrip: function (t) {
    const observed = report(t);
    const out = copyTable(t);
    out.set("observed", observed);
    return out;
  },

  roundTripArray: function (items) {
    const firstPoint = items.length > 0 && items[0] instanceof Map
      ? items[0].get("point") : null;
    const out = [];
    for (let index = 0; index < items.length; index++) {
      const item = items[index];
      const entry = copyTable(item);
      const alias = index === 0 ? "self"
        : String(item.get("point") === firstPoint);
      entry.set("observed", reportElement(item) + "|" + alias);
      out.push(entry);
    }
    return out;
  },
};
