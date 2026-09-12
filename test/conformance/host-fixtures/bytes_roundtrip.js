let calls = 0;
let shared = new Uint8Array(2);
module.exports = {
  echoBytes(b) { calls += 1; return b; },
  nullableBytes(b) { calls += 1; return b; },
  makeBytes(n) { calls += 1; return new Uint8Array(n); },
  sharedBytes() { return shared; },
  readByte(b) { calls += 1; return b[0]; },
  callCount() { return calls; },
  badBytesReturn() { calls += 1; return "not-bytes"; }
};
