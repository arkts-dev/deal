let saved, damaged, callable, body, signature;
module.exports = {
  keep(value) { saved = value; },
  corrupt() { saved[saved.length - 1] = "bad"; },
  repair() { saved[saved.length - 1] = 9; },
  size() { return saved.length; },
  echo(value) { return value; },
  make() { saved = [4, 9]; return saved; },
  grow() { saved.push(11); },
  damage(value, restore) { if (!restore) damaged = value; damaged.x = restore ? 9 : "bad"; },
  poison(value) { callable=value; body=value.$f; signature=value.$sig; value.$f=null; },
  poisonSignature(value) { callable=value; body=value.$f; signature=value.$sig; value.$sig="garbage"; },
  restoreFunction() { callable.$f=body; callable.$sig=signature; }
};
