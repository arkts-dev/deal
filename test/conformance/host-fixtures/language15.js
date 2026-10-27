"use strict";
module.exports = {
  echo: value => value,
  undefinedValue: () => undefined,
  nothing: () => {},
  maybeNumber: value => value,
  maybeString: value => value,
  maybeBoolean: value => value,
  mutate: value => { value[0] = 9; return value; },
  fresh: () => [4],
  mutateClass: value => { value.n = 8; return value; },
  classNumber: value => value.n,
  callback: value => { value.$f(); },
  asyncUnknown: () => Promise.resolve(undefined),
  asyncUndefined: () => Promise.resolve(undefined),
  asyncVoid: () => Promise.resolve(undefined),
  invalidNested: () => new Map([["child", new Map([["bad", "\ud800"]])]]),
  opaque: () => new Date(),
  graph: () => { const value = new Map(); const child = new Map(); value.set("self", value); value.set("a", child); value.set("b", child); return value; }
};
