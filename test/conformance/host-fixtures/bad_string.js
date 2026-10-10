"use strict";

// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings exercise the DEAL v1.2 string carrier classes. JS
// strings are UTF-16, so lone surrogate code units are the JS analog of
// the Lua host's malformed UTF-8 bytes — the declared (string) boundary
// must reject both classes with E8010 and select the pinned reason from
// the offending code point (the embedded surrogate included). The valid
// exports exercise the admitted carriers at scalar and array element
// positions (the raw carrier decodes to the same "ok" string).
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
};
