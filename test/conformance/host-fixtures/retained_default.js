"use strict";

// Host fixture implementation for the legal module-level default-call
// witness: one retained native array is returned by every provider call,
// and the counter plus the declaration-order trace are invocation-local to
// one host module load (a fresh process starts at zero, so any leakage is
// observable in the witness's exact counts).

const original = [1, 2];
let calls = 0;
let order = "";

module.exports = {
  retained: function () {
    return original;
  },
  provideFirst: function () {
    calls = calls + 1;
    order = order + "A";
    return original;
  },
  provideSecond: function () {
    calls = calls + 1;
    order = order + "B";
    return original;
  },
  bump: function () {
    calls = calls + 1;
    return null;
  },
  calls: function () {
    return calls;
  },
  order: function () {
    return order;
  },
};
