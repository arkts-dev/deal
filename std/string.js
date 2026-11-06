"use strict";

const $rt = require("../deal/runtime");
const $string = {};

$string.length = $rt.function("(string)->number", function(s, file, line, column) {
  return $rt.checkString(s, file, line, column).length;
});
$string.substring = $rt.function("(string,number,number)->string", function(s, start, end, file, line, column) {
  $rt.checkString(s, file, line, column);
  $rt.checkNumber(start, file, line, column);
  $rt.checkNumber(end, file, line, column);
  return s.substring(start, end);
});
for (const [name, method] of [["contains", "includes"], ["startsWith", "startsWith"], ["endsWith", "endsWith"]]) {
  $string[name] = $rt.function("(string,string)->boolean", function(s, part, file, line, column) {
    $rt.checkString(s, file, line, column);
    $rt.checkString(part, file, line, column);
    return s[method](part);
  });
}
$string.replace = $rt.function("(string,string,string)->string", function(s, search, replacement, file, line, column) {
  $rt.checkString(s, file, line, column);
  $rt.checkString(search, file, line, column);
  $rt.checkString(replacement, file, line, column);
  return s.replace(search, replacement);
});
$string.split = $rt.function("(string,string)->[string]", function(s, sep, file, line, column) {
  $rt.checkString(s, file, line, column);
  $rt.checkString(sep, file, line, column);
  return $rt.checkArray("[string]", $rt.tagArray(s.split(sep), "string"), file, line, column);
});
$string.trim = $rt.function("(string)->string", function(s, file, line, column) {
  return $rt.checkString(s, file, line, column).trim();
});
module.exports = $string;
