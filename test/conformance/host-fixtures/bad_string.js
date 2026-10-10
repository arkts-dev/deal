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
// elements must stay untouched. The native-* exports return the same
// declared shapes (one JS array here; the JVM host's declared $DealRt
// wrapper there), so every valid control must land as a usable DEAL
// array at every nesting level and every invalid element must be
// rejected by the element check. The nativeDifferingDepthStringArray
// export shares one inner array between two positions with different
// descriptor depths (the same array is checked as the root's first
// element and again under the root's second element at one depth less),
// so every position must re-validate the elements against its own
// descriptor.
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

  nativeBadStringArray: function () {
    return ["ok", "\uD800"];
  },

  nativeBadNestedTail: function () {
    return [["ok"], "not an array"];
  },

  nativeStringArray: function () {
    return ["ok"];
  },

  nativeNestedStringArray: function () {
    return [["ok"]];
  },

  nativeSharedNestedStringArray: function () {
    var shared = ["ok"];
    return [shared, shared];
  },

  nativeDifferingDepthStringArray: function () {
    var s = ["ok"];
    var d = [s];
    var c = [d];
    var e = [c];
    return [c, e];
  },
};
