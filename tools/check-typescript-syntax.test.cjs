#!/usr/bin/env node
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {spawnSync} = require("node:child_process");
const checker = path.join(__dirname, "check-typescript-syntax.cjs");
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "deal-ts-syntax-"));
try {
  const cases = [
    ["valid", "export function probe(): number { return 7; }", null],
    // TypeScript semantic assignability is deliberately not DEAL's contract.
    ["type-only", 'let value: number = "not a number";', null],
    ["parser", "export function probe( {", "TS1005"],
    ["throw-newline", "export function probe() { throw\n new Error(); }", "TS1142"],
    ["legacy-octal", "export let value = 0123;", "TS1085"],
    ["await-context", "export function probe() { await fetchUser(); }", "TS1308"]
  ];
  for (const [name, text, diagnostic] of cases) {
    const file = path.join(directory, name + ".deal");
    fs.writeFileSync(file, text);
    const result = spawnSync(process.execPath, [checker, file], {encoding: "utf8", timeout: 30000});
    assert.ifError(result.error);
    assert.equal(result.signal, null, name + ": interrupted checker");
    assert.equal(result.status, diagnostic ? 1 : 0, name + ": " + result.stdout + result.stderr);
    if (diagnostic) {
      assert.ok(result.stderr.includes(diagnostic), name + ": missing actual compiler diagnostic");
      assert.ok(!result.stdout.includes("zero syntactic/grammar diagnostics"), name + ": false success");
    } else {
      assert.match(result.stdout, /zero syntactic\/grammar diagnostics \(1 inputs\)/);
    }
  }
  console.log("TypeScript syntax checker controls: 6 passed (parser, grammar, context and type-only separation)");
} finally {
  fs.rmSync(directory, {recursive: true, force: true});
}
