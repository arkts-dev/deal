let calls = 0;
let shared = [0, 0];
module.exports = {
  echoArray(b) { calls += 1; return b; },
  nullableArray(b) { calls += 1; return b; },
  makeArray(n) { calls += 1; return new Array(n).fill(0); },
  sharedArray() { return shared; },
  readElement(b) { calls += 1; return b[0]; },
  callCount() { return calls; },
  badArrayReturn() { calls += 1; return "not-array"; }
};
