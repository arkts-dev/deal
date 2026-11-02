let saved;
module.exports = {
  keep(value) { saved = value; },
  echo(value) { return value; },
  mutate(value) { saved[0] = value; },
  corrupt() { saved[0] = "bad"; },
  callback(f, value) { return f.$f(value); }
};
