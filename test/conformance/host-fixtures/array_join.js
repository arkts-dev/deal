let saved;
module.exports = {
  keep(value) { saved = value; },
  replace(index, value) { saved[index] = value; },
  append(value) { saved.push(value); },
  size() { return saved.length; }
};
