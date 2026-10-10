"use strict";

// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings exercise the DEAL v1.2 string carrier classes. JS
// strings are UTF-16, so lone surrogate code units are the JS analog of
// the Lua host's malformed UTF-8 bytes — the declared (string) boundary
// must reject both classes with E8010 and select the pinned reason from
// the offending code point (the embedded surrogate included). The valid
// exports exercise the admitted carriers at scalar and array element
// positions (the raw carrier decodes to the same "ok" string), the
// nullable nested-array element that must stay the language null, one
// inner array returned at both positions of a declared string[][] (the
// same DEAL array, so identity holds and a mutation is observed through
// both aliases), and the rejected nested tail whose retained earlier
// elements must stay untouched.
module.exports = {
  badString: function () {
    return "a\uD800b";
  },

  surrogateString: function () {
    return "\uD800";
  },

  embeddedSurrogateString: function () {
    return "a\uD800b";
  },

  validString: function () {
    return "ok";
  },

  rawStringArray: function () {
    return ["ok"];
  },

  rawNestedStringArray: function () {
    return [["ok"]];
  },

  rawNullableStringArray: function () {
    return ["ok"];
  },

  rawNullableArrayArray: function () {
    return [null, ["ok"]];
  },

  sharedNestedStringArray: function () {
    var shared = ["ok"];
    return [shared, shared];
  },

  badNestedTail: function () {
    return [["ok"], "not an array"];
  },
};
