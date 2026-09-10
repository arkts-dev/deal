let nextValue = 0;
module.exports = {
  intValue() { return 17; },
  stringValue() { return "host"; },
  nullableString(flag) { return flag ? "host" : null; },
  nullValue() { return null; },
  nextValue() { nextValue += 1; return nextValue; },
  echoInt(value) { return value; },
  echoNumber(value) { return value; },
  echoBoolean(value) { return value; },
  echoString(value) { return value; },
  nullableInt(value) { return value; },
  extraExport() { return "ignored"; },
  apply(value, callback) { return callback.f(value); }
};
